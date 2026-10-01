package com.withus.customer.domain;

import java.time.LocalDate;

/** 정규화를 마친 고객 입력값 (등록·수정·업로드 공통). 누적구매액·수신동의는 포함하지 않는다 */
public record CustomerFields(String name, String email, String phone, String regionCode, LocalDate birthDate,
	LocalDate joinedAt) {
}
