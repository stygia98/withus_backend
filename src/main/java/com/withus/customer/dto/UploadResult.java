package com.withus.customer.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** 업로드 결과 (API_SPEC 3장 POST /customers/uploads). 실패 행이 있어도 성공 행은 저장된다 */
public record UploadResult(
	@Schema(description = "데이터 행 수 (헤더·빈 행 제외)") int total,
	int created, int updated, int failed,
	@Schema(description = "과거 수신거부 이력이 있어 해당 채널을 수신거부로 저장한 행 수") int suppressed,
	List<Failure> failures) {

	/** row 는 파일에서 보이는 행 번호 (헤더 = 1), reason 은 오류 코드 */
	public record Failure(@Schema(example = "12") int row, @Schema(example = "CUSTOMER_INVALID_PHONE") String reason) {
	}
}
