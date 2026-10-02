package com.withus.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * mapper/**.xml 전부가 DB·스프링 컨텍스트 없이 구문 오류 없이 읽히는지 확인한다.
 * SQL 안의 '<' 를 &lt; 로 쓰지 않은 것 같은 XML 오류는 컴파일(test-compile)을 통과하고 앱을 띄울 때서야 드러나
 * @SpringBootTest 가 전부 실패한다(PR #31 재리뷰). 이 테스트는 몇 초 만에 같은 오류를 잡는다
 */
class MapperXmlParseAllTest {

	@Test
	void 모든_매퍼_XML이_파싱된다() throws Exception {
		Resource[] mappers = new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/**/*.xml");
		assertThat(mappers).isNotEmpty();

		for (Resource mapper : mappers) {
			Configuration configuration = new Configuration();
			try (InputStream in = mapper.getInputStream()) {
				new XMLMapperBuilder(in, configuration, mapper.getFilename(), configuration.getSqlFragments()).parse();
			} catch (Exception e) {
				throw new AssertionError(mapper.getFilename() + " 파싱 실패: " + rootMessage(e), e);
			}
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
