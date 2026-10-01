package com.withus.customer.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.withus.common.exception.BusinessException;
import com.withus.customer.domain.CustomerErrorCode;

/**
 * 고객 업로드 파일 읽기와 양식 만들기 (PRD F-01). 1행은 고정 헤더, 2행부터 고객
 * 값은 문자열 그대로 돌려주고 검증·정규화는 CustomerUploadService 가 한다
 */
public final class CustomerUploadFile {

	/** 고정 헤더 (PRD F-01 순서) */
	public static final List<String> HEADERS = List.of("이름", "이메일", "휴대폰", "지역", "생년월일", "가입일", "누적구매액",
		"이메일수신동의(Y/N)", "SMS수신동의(Y/N)");
	public static final int MAX_ROWS = 10_000;
	static final int PHONE = 2;
	private static final List<Integer> TEXT_COLUMNS = List.of(PHONE, 4, 5);

	/** 한 행. row 는 사용자가 파일에서 보는 행 번호 (헤더 = 1) */
	public record Line(int row, List<String> cells) {

		public String get(int i) {
			return i < cells.size() ? cells.get(i).trim() : "";
		}
	}

	private CustomerUploadFile() {
	}

	/** 확장자로 xlsx·csv 를 고른다. 빈 행은 건너뛴다 */
	public static List<Line> read(String filename, byte[] bytes) {
		String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
		if (name.endsWith(".xlsx")) {
			return xlsx(bytes);
		}
		if (name.endsWith(".csv")) {
			return csv(bytes);
		}
		throw new BusinessException(CustomerErrorCode.UPLOAD_INVALID_FILE);
	}

	/** 양식 xlsx — 헤더 1행. 휴대폰·날짜 칸은 텍스트 서식이라 엑셀이 앞자리 0·날짜를 바꾸지 않는다 */
	public static byte[] template() {
		try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = wb.createSheet("고객");
			CellStyle text = wb.createCellStyle();
			text.setDataFormat(wb.createDataFormat().getFormat("@"));
			Row header = sheet.createRow(0);
			for (int i = 0; i < HEADERS.size(); i++) {
				header.createCell(i).setCellValue(HEADERS.get(i));
				sheet.setColumnWidth(i, 18 * 256);
			}
			TEXT_COLUMNS.forEach(i -> sheet.setDefaultColumnStyle(i, text));
			wb.write(out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("업로드 양식을 만들지 못했습니다", e);
		}
	}

	private static List<Line> csv(byte[] bytes) {
		List<Line> lines = new ArrayList<>();
		try {
			for (CSVRecord r : CSVFormat.DEFAULT.parse(new StringReader(decode(bytes)))) {
				add(lines, (int) r.getRecordNumber(), r.toList());
			}
		} catch (IOException | IllegalStateException | IllegalArgumentException e) {
			throw new BusinessException(CustomerErrorCode.UPLOAD_INVALID_FILE);
		}
		return checkHeader(lines);
	}

	/** BOM 있으면 UTF-8, 없으면 UTF-8 로 엄격하게 읽어 보고 실패하면 MS949 (엑셀 "CSV" 저장 기본값) */
	static String decode(byte[] bytes) {
		if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
			return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
		}
		try {
			return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			return new String(bytes, Charset.forName("MS949"));
		}
	}

	private static List<Line> xlsx(byte[] bytes) {
		List<Line> lines = new ArrayList<>();
		try (InputStream in = new ByteArrayInputStream(bytes); XSSFWorkbook wb = new XSSFWorkbook(in)) {
			Sheet sheet = wb.getSheetAt(0);
			for (Row row : sheet) {
				List<String> cells = new ArrayList<>();
				for (int i = 0; i < HEADERS.size(); i++) {
					cells.add(cellText(row.getCell(i), i == PHONE));
				}
				add(lines, row.getRowNum() + 1, cells);
			}
		} catch (BusinessException e) {
			throw e;
		} catch (Exception e) {
			// 손상된 파일, xlsx 가 아닌 파일, 압축 폭탄 방지(POI ZipSecureFile) 등
			throw new BusinessException(CustomerErrorCode.UPLOAD_INVALID_FILE);
		}
		return checkHeader(lines);
	}

	/** 빈 행은 건너뛰고, 데이터가 10,000행을 넘으면 바로 멈춘다 */
	private static void add(List<Line> lines, int row, List<String> cells) {
		if (cells.stream().allMatch(c -> c == null || c.isBlank())) {
			return;
		}
		if (lines.size() > MAX_ROWS) { // 헤더 1 + 데이터 10,000
			throw new BusinessException(CustomerErrorCode.UPLOAD_TOO_MANY_ROWS);
		}
		lines.add(new Line(row, cells.stream().map(c -> c == null ? "" : c).toList()));
	}

	/** 첫 (비어 있지 않은) 행이 헤더와 같은지 보고 헤더를 뺀 데이터 행만 돌려준다. 공백과 "(Y/N)" 표기 차이는 허용 */
	private static List<Line> checkHeader(List<Line> lines) {
		if (lines.isEmpty() || !normalize(lines.get(0).cells()).equals(normalize(HEADERS))) {
			throw new BusinessException(CustomerErrorCode.UPLOAD_INVALID_HEADER);
		}
		return lines.subList(1, lines.size());
	}

	private static List<String> normalize(List<String> headers) {
		List<String> result = new ArrayList<>(headers.stream()
			.map(h -> h.replace("﻿", "").replace("(Y/N)", "").replaceAll("\\s", "").toUpperCase(Locale.ROOT))
			.toList());
		while (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) {
			result.remove(result.size() - 1);
		}
		return result;
	}

	private static String cellText(Cell cell, boolean phone) {
		if (cell == null) {
			return "";
		}
		CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
		return switch (type) {
			case STRING -> cell.getStringCellValue();
			case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
			case NUMERIC -> {
				if (DateUtil.isCellDateFormatted(cell)) {
					yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
				}
				String n = BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
				// 엑셀이 숫자로 바꾸며 지운 휴대폰 앞자리 0 복원 (01012345678 → 1012345678)
				yield phone && n.matches("1\\d{8,9}") ? "0" + n : n;
			}
			default -> "";
		};
	}
}
