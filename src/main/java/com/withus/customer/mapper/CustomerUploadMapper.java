package com.withus.customer.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.withus.customer.domain.Customer;
import com.withus.customer.service.CustomerUploadService.HistoryRow;
import com.withus.customer.service.CustomerUploadService.NewRow;
import com.withus.customer.service.CustomerUploadService.UpdateRow;

/** 고객 업로드 일괄 처리 (PRD 9장: 500행 단위). 목록 인자는 호출 쪽에서 500개씩 나눠 넘긴다 */
@Mapper
public interface CustomerUploadMapper {

	/** 삭제되지 않은 고객만 (업로드의 같은 고객 판단 기준, PRD 7장) */
	List<Customer> findActiveByEmails(@Param("emails") List<String> emails);

	/** "EMAIL:값" / "SMS:값" 형태 */
	List<String> findSuppressions(@Param("emails") List<String> emails, @Param("phones") List<String> phones);

	void insertBatch(@Param("rows") List<NewRow> rows);

	/** 방금 넣은 고객 중 동의 Y 인 채널을 최초 이력으로 남긴다 (before_yn NULL, source UPLOAD) */
	void insertInitialHistory(@Param("emails") List<String> emails);

	int update(@Param("r") UpdateRow row);

	void insertHistoryBatch(@Param("rows") List<HistoryRow> rows);
}
