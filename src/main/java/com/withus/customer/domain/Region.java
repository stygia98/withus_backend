package com.withus.customer.domain;

import java.util.List;

import com.withus.common.exception.BusinessException;

/**
 * 시·도 코드 (PRD F-01: 시/도명 → 코드). customer.region_code 와 세그먼트 region 조건에 쓴다
 * 코드 전체 목록은 이 enum 이 기준이다 (DB_SCHEMA 시드 절: 팀원1이 정규화 매핑에서 확정)
 */
public enum Region {

	SEOUL("서울특별시", "서울"),
	BUSAN("부산광역시", "부산"),
	DAEGU("대구광역시", "대구"),
	INCHEON("인천광역시", "인천"),
	GWANGJU("광주광역시", "광주"),
	DAEJEON("대전광역시", "대전"),
	ULSAN("울산광역시", "울산"),
	SEJONG("세종특별자치시", "세종"),
	GYEONGGI("경기도", "경기"),
	GANGWON("강원특별자치도", "강원", "강원도"),
	CHUNGBUK("충청북도", "충북"),
	CHUNGNAM("충청남도", "충남"),
	JEONBUK("전북특별자치도", "전북", "전라북도"),
	JEONNAM("전라남도", "전남"),
	GYEONGBUK("경상북도", "경북"),
	GYEONGNAM("경상남도", "경남"),
	JEJU("제주특별자치도", "제주", "제주도");

	/** 정식명, 약칭, 옛 명칭 순 */
	private final List<String> names;

	Region(String... names) {
		this.names = List.of(names);
	}

	/** 화면·치환자({{region}})에 쓰는 표시명 = 약칭 ("서울"). 프론트 지역 목록과 같다 */
	public String displayName() {
		return names.get(1);
	}

	/** 코드(대소문자 무시)·정식명·약칭·옛 명칭으로 찾는다. 없으면 CUSTOMER_INVALID_REGION */
	public static Region from(String value) {
		String v = value == null ? "" : value.trim();
		for (Region r : values()) {
			if (r.name().equalsIgnoreCase(v) || r.names.contains(v)) {
				return r;
			}
		}
		throw new BusinessException(CustomerErrorCode.CUSTOMER_INVALID_REGION);
	}
}
