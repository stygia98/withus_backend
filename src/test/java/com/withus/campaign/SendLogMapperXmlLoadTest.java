package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/**
 * SendLogMapper.xml이 DB 연결 없이도 구문·타입 참조 오류 없이 파싱되는지 확인한다.
 * sendLogResultMap(생성자 매핑, SendLog 가 @Builder 전용이라 no-arg 생성자가 없어서 쓴다)은
 * javaType·typeHandler 가 build 시점에 바로 검증돼, UUID 처럼 기본 TypeHandler 가 없는 타입을
 * 잘못 선언하면 애플리케이션을 실제로 띄우기 전에는(즉 docker DB 없이는) 못 잡는다 — 이 테스트가 그 역할을 한다
 */
class SendLogMapperXmlLoadTest {

	@Test
	void mapper_xml이_오류_없이_파싱된다() throws Exception {
		Configuration configuration = new Configuration();
		try (InputStream in = Resources.getResourceAsStream("mapper/campaign/SendLogMapper.xml")) {
			new XMLMapperBuilder(in, configuration, "mapper/campaign/SendLogMapper.xml", configuration.getSqlFragments())
				.parse();
		}

		var resultMap = configuration.getResultMap("com.withus.campaign.mapper.SendLogMapper.sendLogResultMap");
		assertThat(resultMap).isNotNull();
		assertThat(resultMap.getConstructorResultMappings()).hasSize(19);
		assertThat(configuration.getMappedStatementNames())
			.contains("com.withus.campaign.mapper.SendLogMapper.claimBatch",
				"com.withus.campaign.mapper.SendLogMapper.findByProviderMessageId");
	}
}
