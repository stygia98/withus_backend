package com.withus.customer.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.withus.common.domain.Channel;
import com.withus.common.exception.BusinessException;
import com.withus.customer.domain.Customer;
import com.withus.customer.domain.CustomerErrorCode;
import com.withus.customer.domain.CustomerFields;
import com.withus.customer.dto.UploadResult;
import com.withus.customer.mapper.CustomerUploadMapper;
import com.withus.customer.service.CustomerUploadFile.Line;

import lombok.RequiredArgsConstructor;

/**
 * 고객 업로드 (PRD F-01, 7장, 9장). 행마다 검증해 실패 행은 사유와 함께 돌려주고 성공 행은 저장한다
 * - 삭제되지 않은 고객과 이메일이 같으면 갱신(누적구매액 유지), 아니면 신규(source=UPLOAD)
 * - suppression 에 있는 채널은 파일 값과 관계없이 동의 N (업로드로는 해제되지 않음)
 * - 업로드 고객은 워크플로우 "신규 고객 등록" 트리거를 발생시키지 않는다
 */
@Service
@RequiredArgsConstructor
public class CustomerUploadService {

	static final long MAX_BYTES = 10L * 1024 * 1024;
	static final int CHUNK = 500;
	private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

	private final CustomerUploadMapper uploadMapper;

	/** 신규 행. emailYn·smsYn 은 suppression 반영이 끝난 값 */
	public record NewRow(CustomerFields f, long totalPurchase, String emailYn, String smsYn) {
	}

	/** 갱신 행. f 의 null 은 "파일에서 빈 칸 → 기존 값 유지" */
	public record UpdateRow(long customerId, CustomerFields f, String emailYn, String smsYn) {
	}

	public record HistoryRow(long customerId, Channel channel, String beforeYn, String afterYn) {
	}

	/** 검증을 통과한 행. 동의 null 은 빈 칸 (신규 N, 갱신은 기존 값 유지) */
	private record Valid(int row, CustomerFields f, Long totalPurchase, String emailYn, String smsYn) {
	}

	@Transactional
	public UploadResult upload(String filename, byte[] bytes) {
		if (bytes.length > MAX_BYTES) {
			throw new BusinessException(CustomerErrorCode.UPLOAD_FILE_TOO_LARGE);
		}
		List<Line> lines = CustomerUploadFile.read(filename, bytes);

		List<UploadResult.Failure> failures = new ArrayList<>();
		Map<String, Valid> byEmail = new LinkedHashMap<>();
		for (Line line : lines) {
			try {
				Valid v = validate(line);
				if (byEmail.putIfAbsent(v.f().email(), v) != null) {
					// 같은 파일 안 중복 이메일은 앞 행만 쓴다
					failures.add(new UploadResult.Failure(line.row(), CustomerErrorCode.CUSTOMER_DUPLICATE_EMAIL.code()));
				}
			} catch (BusinessException e) {
				failures.add(new UploadResult.Failure(line.row(), e.getErrorCode().code()));
			}
		}

		List<String> emails = List.copyOf(byEmail.keySet());
		Map<String, Customer> existing = new LinkedHashMap<>();
		chunks(emails).forEach(c -> uploadMapper.findActiveByEmails(c).forEach(x -> existing.put(x.getEmail(), x)));
		Set<String> suppressed = new HashSet<>();
		List<String> phones = byEmail.values().stream().map(v -> v.f().phone()).filter(Objects::nonNull).distinct()
			.toList();
		// 휴대폰은 이메일이 있는 행에서만 나오므로 개수가 이메일 이하다
		for (int i = 0; i < emails.size(); i += CHUNK) {
			suppressed.addAll(uploadMapper.findSuppressions(slice(emails, i), slice(phones, i)));
		}

		List<NewRow> inserts = new ArrayList<>();
		List<UpdateRow> updates = new ArrayList<>();
		List<HistoryRow> history = new ArrayList<>();
		int suppressedRows = 0;
		for (Valid v : byEmail.values()) {
			boolean emailSup = suppressed.contains("EMAIL:" + v.f().email());
			boolean smsSup = v.f().phone() != null && suppressed.contains("SMS:" + v.f().phone());
			suppressedRows += emailSup || smsSup ? 1 : 0;
			Customer old = existing.get(v.f().email());
			if (old == null) {
				inserts.add(new NewRow(v.f(), Objects.requireNonNullElse(v.totalPurchase(), 0L),
					consent(v.emailYn(), "N", emailSup), consent(v.smsYn(), "N", smsSup)));
				continue;
			}
			String emailYn = consent(v.emailYn(), old.getEmailConsentYn(), emailSup);
			String smsYn = consent(v.smsYn(), old.getSmsConsentYn(), smsSup);
			updates.add(new UpdateRow(old.getCustomerId(), v.f(), emailYn, smsYn));
			if (!emailYn.equals(old.getEmailConsentYn())) {
				history.add(new HistoryRow(old.getCustomerId(), Channel.EMAIL, old.getEmailConsentYn(), emailYn));
			}
			if (!smsYn.equals(old.getSmsConsentYn())) {
				history.add(new HistoryRow(old.getCustomerId(), Channel.SMS, old.getSmsConsentYn(), smsYn));
			}
		}

		chunks(inserts).forEach(uploadMapper::insertBatch);
		chunks(inserts.stream().map(r -> r.f().email()).toList()).forEach(uploadMapper::insertInitialHistory);
		// ponytail: 갱신은 행별 UPDATE (10,000행 약 8초, 목표 30초). 느려지면 UPDATE ... FROM (VALUES ...) 일괄로
		updates.forEach(uploadMapper::update);
		chunks(history).forEach(uploadMapper::insertHistoryBatch);

		failures.sort(Comparator.comparingInt(UploadResult.Failure::row));
		return new UploadResult(lines.size(), inserts.size(), updates.size(), failures.size(), suppressedRows,
			failures);
	}

	/** 행 하나를 검증·정규화한다. 첫 번째로 걸린 칸의 오류 코드를 던진다 */
	private static Valid validate(Line line) {
		String name = blankToNull(line.get(0));
		if (name != null && name.length() > 50) {
			throw new BusinessException(CustomerErrorCode.CUSTOMER_INVALID_NAME);
		}
		String email = CustomerNormalizer.email(line.get(1));
		if (email.isEmpty() || email.length() > 255 || !EMAIL.matcher(email).matches()) {
			throw new BusinessException(CustomerErrorCode.CUSTOMER_INVALID_EMAIL);
		}
		String phone = CustomerNormalizer.phone(line.get(2));
		String region = CustomerNormalizer.regionCode(line.get(3));
		LocalDate birth = CustomerNormalizer.date(line.get(4));
		LocalDate joined = CustomerNormalizer.date(line.get(5));
		if (joined == null) {
			throw new BusinessException(CustomerErrorCode.CUSTOMER_INVALID_DATE);
		}
		return new Valid(line.row(), new CustomerFields(name, email, phone, region, birth, joined),
			amount(line.get(6)), yn(line.get(7)), yn(line.get(8)));
	}

	/** 빈 칸이면 null. 천 단위 쉼표는 허용 */
	private static Long amount(String value) {
		String s = value.replace(",", "");
		if (s.isEmpty()) {
			return null;
		}
		if (!s.matches("\\d{1,18}")) {
			throw new BusinessException(CustomerErrorCode.CUSTOMER_INVALID_AMOUNT);
		}
		return Long.parseLong(s);
	}

	private static String yn(String value) {
		String s = value.toUpperCase(Locale.ROOT);
		if (s.isEmpty()) {
			return null;
		}
		if (!s.equals("Y") && !s.equals("N")) {
			throw new BusinessException(CustomerErrorCode.CUSTOMER_INVALID_CONSENT);
		}
		return s;
	}

	/** suppression 이면 N, 빈 칸이면 기본값(신규 N, 갱신은 기존 값), 아니면 파일 값 */
	private static String consent(String fromFile, String fallback, boolean suppressed) {
		return suppressed ? "N" : fromFile == null ? fallback : fromFile;
	}

	private static String blankToNull(String value) {
		return value.isBlank() ? null : value.trim();
	}

	private static <T> List<List<T>> chunks(List<T> list) {
		List<List<T>> result = new ArrayList<>();
		for (int i = 0; i < list.size(); i += CHUNK) {
			result.add(slice(list, i));
		}
		return result;
	}

	private static <T> List<T> slice(List<T> list, int from) {
		return from >= list.size() ? List.of() : list.subList(from, Math.min(from + CHUNK, list.size()));
	}
}
