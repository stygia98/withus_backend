package com.withus.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * mapper/**.xml 전부가 DB·스프링 컨텍스트 없이 읽히는지 확인한다. SQL 안의 '<' 를 &lt; 로 쓰지 않은 것 같은 XML 오류는
 * 컴파일(test-compile)을 통과하고 앱을 띄울 때서야 드러나 @SpringBootTest 가 전부 실패한다(PR #31 재리뷰).
 * 하나의 Configuration 에 전부 parse 한 뒤 구문 조립(buildAllStatements)까지 해, 매퍼 사이의 참조(include refid)와
 * 동적 SQL 구성 오류까지 잡는다
 */
class MapperXmlParseAllTest {

	@Test
	void 모든_매퍼_XML이_파싱되고_구문이_만들어진다() throws Exception {
		Resource[] mappers = new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/**/*.xml");
		assertThat(mappers).isNotEmpty();

		Configuration configuration = new Configuration();
		for (Resource mapper : mappers) {
			try (InputStream in = mapper.getInputStream()) {
				new XMLMapperBuilder(in, configuration, mapper.getFilename(), configuration.getSqlFragments()).parse();
			} catch (Exception e) {
				throw new AssertionError(mapper.getFilename() + " 파싱 실패: " + rootMessage(e), e);
			}
		}
		// getMappedStatementNames() 가 내부에서 buildAllStatements() 를 불러 미해결 참조(incomplete statement)를 드러낸다
		try {
			assertThat(configuration.getMappedStatementNames()).isNotEmpty();
		} catch (RuntimeException e) {
			throw new AssertionError("매퍼 구문 조립 실패: " + rootMessage(e), e);
		}
	}

	private static String rootMessage(Throwable e) {
		Throwable t = e;
		while (t.getCause() != null) {
			t = t.getCause();
		}
		return t.getMessage();
	}
}
