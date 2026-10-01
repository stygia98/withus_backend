package com.withus.segment.domain;

import java.time.OffsetDateTime;

import lombok.Getter;

/** segment + segment_rule (DB_SCHEMA 5·6번). ruleJson 은 JSONB 원문 텍스트 */
@Getter
public class Segment {

	private Long segmentId;
	private String name;
	private String description;
	private String ruleJson;
	private Long createdBy;
	private OffsetDateTime createdAt;
	private OffsetDateTime updatedAt;

	public static Segment create(String name, String description, String ruleJson, long createdBy) {
		Segment s = new Segment();
		s.name = name;
		s.description = description;
		s.ruleJson = ruleJson;
		s.createdBy = createdBy;
		return s;
	}
}
