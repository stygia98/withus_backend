package com.withus.campaign.domain;

/** send_log.kind (DB_SCHEMA 13번). 워크플로우 SEND 는 instance_id 로 구분하고 kind 는 CAMPAIGN 을 쓴다 */
public enum SendKind {
	CAMPAIGN, NOTICE, TEST
}
