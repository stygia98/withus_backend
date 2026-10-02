package com.withus.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** WorkflowInstanceMapper.xml 이 DB 연결 없이도 구문 오류 없이 파싱되는지 확인한다 (docker 없는 환경용) */
class WorkflowInstanceMapperXmlLoadTest {

	@Test
	void mapper_xml이_오류_없이_파싱된다() throws Exception {
		Configuration configuration = new Configuration();
		try (InputStream in = Resources.getResourceAsStream("mapper/workflow/WorkflowInstanceMapper.xml")) {
			new XMLMapperBuilder(in, configuration, "mapper/workflow/WorkflowInstanceMapper.xml",
				configuration.getSqlFragments()).parse();
		}

		assertThat(configuration.getMappedStatementNames())
			.contains("com.withus.workflow.mapper.WorkflowInstanceMapper.claimBatch");
	}
}
