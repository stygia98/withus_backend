package com.withus.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.withus.auth.domain.Role;
import com.withus.auth.security.AuthMember;
import com.withus.common.TestCsrf;
import com.withus.customer.service.CustomerUploadFile;

/** 고객 업로드 (PRD F-01·9장·10.3, API_SPEC 3장). 로컬 Docker DB, 테스트마다 롤백 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CustomerUploadApiTest {

	static final String HEADER = String.join(",", CustomerUploadFile.HEADERS);

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;

	String tag;

	@BeforeEach
	void setUp() {
		tag = UUID.randomUUID().toString().substring(0, 8);
	}

	@Test
	void 신규_갱신_수신거부_실패를_행별로_처리하고_성공_행은_저장한다() throws Exception {
		// 기존 고객: 누적구매액 50,000, 메일 동의 Y, 휴대폰 있음
		jdbc.update("""
			INSERT INTO customer (name, email, phone, joined_at, total_purchase, email_consent_yn, email_consent_at,
			                      sms_consent_yn, source)
			VALUES ('기존', ?, '01011112222', DATE '2025-01-01', 50000, 'Y', now(), 'Y', 'MANUAL')
			""", mail("old"));
		jdbc.update("INSERT INTO suppression (channel, value, reason) VALUES ('EMAIL', ?, 'UNSUBSCRIBE')", mail("sup"));

		String csv = String.join("\n", HEADER,
			"신규,%s,010-3333-4444,서울,1990-05-01,2026-09-01,\"12,000\",Y,N".formatted(mail("new").toUpperCase()), // 2
			"이름변경,%s,,경기,,2026-09-02,0,N,".formatted(mail("old")), //                                       3
			"거부이력,%s,,부산,,2026-09-03,,Y,Y".formatted(mail("sup")), //                                        4
			"휴대폰오류,%s,02-123-4567,,,2026-09-04,,,".formatted(mail("e1")), //                                  5
			"날짜오류,%s,,,,2026/09/04,,,".formatted(mail("e2")), //                                              6
			"이메일오류,not-an-email,,,,2026-09-04,,,", //                                                         7
			"중복,%s,,,,2026-09-05,,,".formatted(mail("new")), //                                                  8
			"동의오류,%s,,,,2026-09-05,,X,".formatted(mail("e3")), //                                             9
			"금액오류,%s,,,,2026-09-05,-1,,".formatted(mail("e4")), //                                           10
			",,,,,,,,", //                                                                    빈 행은 건너뜀 (11)
			"지역오류,%s,,화성,,2026-09-05,,,".formatted(mail("e5"))); //                                        12

		upload("customers.csv", csv.getBytes(StandardCharsets.UTF_8), Role.MANAGER).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.total").value(10))
			.andExpect(jsonPath("$.data.created").value(2))
			.andExpect(jsonPath("$.data.updated").value(1))
			.andExpect(jsonPath("$.data.failed").value(7))
			.andExpect(jsonPath("$.data.suppressed").value(1))
			.andExpect(jsonPath("$.data.failures[0].row").value(5))
			.andExpect(jsonPath("$.data.failures[0].reason").value("CUSTOMER_INVALID_PHONE"))
			.andExpect(jsonPath("$.data.failures[*].reason").value(org.hamcrest.Matchers.contains(
				"CUSTOMER_INVALID_PHONE", "CUSTOMER_INVALID_DATE", "CUSTOMER_INVALID_EMAIL", "CUSTOMER_DUPLICATE_EMAIL",
				"CUSTOMER_INVALID_CONSENT", "CUSTOMER_INVALID_AMOUNT", "CUSTOMER_INVALID_REGION")));

		Map<String, Object> created = row(mail("new"));
		assertThat(created).containsEntry("name", "신규").containsEntry("phone", "01033334444")
			.containsEntry("region_code", "SEOUL").containsEntry("total_purchase", 12000L)
			.containsEntry("email_consent_yn", "Y").containsEntry("sms_consent_yn", "N").containsEntry("source", "UPLOAD");
		assertThat(history(mail("new"))).containsExactly("EMAIL:->Y:UPLOAD");

		Map<String, Object> updated = row(mail("old"));
		assertThat(updated).as("누적구매액은 파일 값(0)으로 바뀌지 않는다").containsEntry("total_purchase", 50000L)
			.containsEntry("name", "이름변경").containsEntry("region_code", "GYEONGGI")
			.as("빈 칸은 기존 값 유지").containsEntry("phone", "01011112222").containsEntry("sms_consent_yn", "Y")
			.containsEntry("email_consent_yn", "N").containsEntry("email_consent_at", null)
			.containsEntry("source", "MANUAL");
		assertThat(history(mail("old"))).containsExactly("EMAIL:Y>N:UPLOAD");

		Map<String, Object> sup = row(mail("sup"));
		assertThat(sup).as("수신거부 채널은 파일이 Y 여도 N").containsEntry("email_consent_yn", "N")
			.containsEntry("sms_consent_yn", "Y");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM suppression WHERE value = ?", Long.class, mail("sup")))
			.as("업로드로는 수신거부가 해제되지 않는다").isOne();
	}

	@Test
	void 휴대폰_칸을_비워도_기존_번호가_수신거부면_SMS_동의는_N_유지() throws Exception {
		String phone = "010" + (10_000_000 + (int) (Math.random() * 89_999_999));
		jdbc.update("""
			INSERT INTO customer (email, phone, joined_at, sms_consent_yn, source)
			VALUES (?, ?, DATE '2025-01-01', 'N', 'MANUAL')
			""", mail("smsup"), phone);
		jdbc.update("INSERT INTO suppression (channel, value, reason) VALUES ('SMS', ?, 'UNSUBSCRIBE')", phone);

		String csv = HEADER + "\n,%s,,,,2026-09-01,,,Y".formatted(mail("smsup"));
		upload("c.csv", csv.getBytes(StandardCharsets.UTF_8), Role.MANAGER)
			.andExpect(jsonPath("$.data.updated").value(1))
			.andExpect(jsonPath("$.data.suppressed").value(1));

		assertThat(row(mail("smsup"))).containsEntry("phone", phone).containsEntry("sms_consent_yn", "N");
		assertThat(history(mail("smsup"))).isEmpty();
	}

	@Test
	void 하이픈_휴대폰도_수신거부_목록의_숫자_번호와_같게_비교한다_PRD_10_3() throws Exception {
		String digits = "010" + String.format("%08d", Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 100_000_000L));
		jdbc.update("INSERT INTO suppression (channel, value, reason) VALUES ('SMS', ?, 'UNSUBSCRIBE')", digits);
		String hyphen = digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7);

		String csv = HEADER + "\n,%s,%s,,,2026-09-01,,Y,Y".formatted(mail("hyphen"), hyphen);
		upload("h.csv", csv.getBytes(StandardCharsets.UTF_8), Role.MANAGER)
			.andExpect(jsonPath("$.data.suppressed").value(1));

		assertThat(row(mail("hyphen"))).containsEntry("phone", digits).containsEntry("sms_consent_yn", "N")
			.containsEntry("email_consent_yn", "Y");
	}

	@Test
	void 엑셀에서_저장한_CP949_CSV() throws Exception {
		String csv = HEADER + "\r\n홍길동," + mail("cp") + ",01055556666,서울특별시,,2026-09-01,,Y,Y\r\n";
		upload("고객.csv", csv.getBytes(Charset.forName("MS949")), Role.OWNER)
			.andExpect(jsonPath("$.data.created").value(1));
		assertThat(row(mail("cp"))).containsEntry("name", "홍길동").containsEntry("region_code", "SEOUL");
	}

	@Test
	void xlsx_날짜_셀과_앞자리_0이_빠진_휴대폰() throws Exception {
		byte[] xlsx = xlsx(wb -> {
			Sheet s = wb.getSheetAt(0);
			CellStyle date = wb.createCellStyle();
			date.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
			Row r = s.createRow(1);
			r.createCell(0).setCellValue("엑셀");
			r.createCell(1).setCellValue(mail("xl"));
			r.createCell(2).setCellValue(1077778888d); // 엑셀이 숫자로 바꾼 01077778888
			r.createCell(3).setCellValue("경기도");
			r.createCell(5).setCellValue(LocalDate.of(2026, 9, 1));
			r.getCell(5).setCellStyle(date);
			r.createCell(6).setCellValue(45000d);
			r.createCell(7).setCellValue("y");
		});
		upload("고객.xlsx", xlsx, Role.MANAGER).andExpect(jsonPath("$.data.created").value(1))
			.andExpect(jsonPath("$.data.failed").value(0));
		assertThat(row(mail("xl"))).containsEntry("phone", "01077778888").containsEntry("region_code", "GYEONGGI")
			.containsEntry("joined_at", java.sql.Date.valueOf("2026-09-01")).containsEntry("total_purchase", 45000L)
			.containsEntry("email_consent_yn", "Y");
	}

	@Test
	void 파일_전체_오류는_400() throws Exception {
		upload("c.csv", "이름,메일\n가,b@c.d".getBytes(StandardCharsets.UTF_8), Role.MANAGER)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("UPLOAD_INVALID_HEADER"));
		upload("c.txt", HEADER.getBytes(StandardCharsets.UTF_8), Role.MANAGER)
			.andExpect(jsonPath("$.error.code").value("UPLOAD_INVALID_FILE"));
		upload("c.xlsx", "not xlsx".getBytes(StandardCharsets.UTF_8), Role.MANAGER)
			.andExpect(jsonPath("$.error.code").value("UPLOAD_INVALID_FILE"));
		upload("c.csv", rows(10_001, 0).getBytes(StandardCharsets.UTF_8), Role.MANAGER)
			.andExpect(jsonPath("$.error.code").value("UPLOAD_TOO_MANY_ROWS"));
		upload("c.csv", HEADER.getBytes(StandardCharsets.UTF_8), Role.STAFF).andExpect(status().isForbidden());
	}

	@Test
	void 만_행을_30초_안에_넣고_다시_올리면_모두_갱신한다() throws Exception {
		byte[] csv = rows(10_000, 1).getBytes(StandardCharsets.UTF_8);
		long t0 = System.nanoTime();
		upload("big.csv", csv, Role.MANAGER).andExpect(jsonPath("$.data.created").value(10_000));
		long insertMs = (System.nanoTime() - t0) / 1_000_000;
		t0 = System.nanoTime();
		upload("big.csv", csv, Role.MANAGER).andExpect(jsonPath("$.data.updated").value(10_000));
		long updateMs = (System.nanoTime() - t0) / 1_000_000;
		System.out.printf("[업로드 성능] 10,000행 신규 %d ms, 갱신 %d ms%n", insertMs, updateMs);
		assertThat(insertMs).as("PRD 9장: 10,000행 30초 이내").isLessThan(30_000);
		assertThat(updateMs).isLessThan(30_000);
	}

	@Test
	void 양식_xlsx_는_고정_헤더() throws Exception {
		byte[] body = mvc.perform(get("/api/v1/customers/upload-template").with(auth(Role.MANAGER)))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type",
				"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
			.andReturn().getResponse().getContentAsByteArray();
		try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(body))) {
			Row h = wb.getSheetAt(0).getRow(0);
			List<String> headers = new ArrayList<>();
			h.forEach(c -> headers.add(c.getStringCellValue()));
			assertThat(headers).isEqualTo(CustomerUploadFile.HEADERS);
		}
		upload("양식.xlsx", body, Role.MANAGER).andExpect(jsonPath("$.data.total").value(0));
	}

	/** count 행. sms 동의 칸까지 채운 정상 행 */
	private String rows(int count, int consentY) {
		StringBuilder sb = new StringBuilder(HEADER);
		for (int i = 0; i < count; i++) {
			sb.append("\n대량").append(i).append(',').append(mail("bulk" + i)).append(",010")
				.append(String.format("%08d", i)).append(",서울,1990-01-01,2026-09-01,1000,")
				.append(consentY == 1 ? "Y" : "N").append(",N");
		}
		return sb.toString();
	}

	private String mail(String prefix) {
		return prefix + "-" + tag + "@upload.local";
	}

	private Map<String, Object> row(String email) {
		return jdbc.queryForMap("SELECT * FROM customer WHERE email = ? AND deleted_yn = 'N'", email);
	}

	private List<String> history(String email) {
		return jdbc.queryForList("""
			SELECT h.channel || ':' || coalesce(h.before_yn, '-') || '>' || h.after_yn || ':' || h.source
			FROM consent_history h JOIN customer c ON c.customer_id = h.customer_id
			WHERE c.email = ? ORDER BY h.history_id
			""", String.class, email);
	}

	private ResultActions upload(String filename, byte[] bytes, Role role) throws Exception {
		return mvc.perform(multipart("/api/v1/customers/uploads").file(new MockMultipartFile("file", filename,
			"application/octet-stream", bytes)).with(auth(role)).with(TestCsrf.issue(mvc)));
	}

	private RequestPostProcessor auth(Role role) {
		return authentication(new UsernamePasswordAuthenticationToken(new AuthMember(1L, role), null,
			List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
	}

	private static byte[] xlsx(java.util.function.Consumer<XSSFWorkbook> fill) throws Exception {
		try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(CustomerUploadFile.template()));
			ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			fill.accept(wb);
			wb.write(out);
			return out.toByteArray();
		}
	}
}
