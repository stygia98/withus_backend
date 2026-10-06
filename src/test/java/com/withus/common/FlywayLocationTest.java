package com.withus.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 운영 Flyway location(classpath:db/migration)에 local 시드가 섞이지 않게 막는다 (이슈 #46).
 * Flyway 는 location 을 하위 폴더까지 재귀로 스캔하므로 db/migration/local 처럼 하위에 두면 운영에도 적용된다.
 * local 시드는 db/seed/local 에 두고 application-local.yml 에서만 location 으로 추가한다. DB·Spring 컨텍스트 없이 돈다.
 */
class FlywayLocationTest {

	private static final PathMatchingResourcePatternResolver RESOLVER = new PathMatchingResourcePatternResolver();

	@Test
	void 운영_location_아래에는_반복_시드가_없다() throws IOException {
		List<String> scripts = names("classpath*:db/migration/**/*.sql");

		assertThat(scripts).as("db/migration 아래(하위 폴더 포함) 마이그레이션").isNotEmpty();
		assertThat(scripts).as("운영에 적용되면 안 되는 시드 스크립트가 db/migration 아래에 있다 — db/seed/local 로 옮겨야 한다")
			.noneMatch(path -> path.contains("seed"));
	}

	@Test
	void local_시드는_db_seed_local_에_있다() throws IOException {
		assertThat(names("classpath*:db/seed/local/R__seed_local.sql")).hasSize(1);
	}

	/**
	 * Flyway 는 반복 마이그레이션(R__)을 설명(파일명의 R__ 뒤)의 사전순으로 실행한다. 다른 local 시드는 고객·쿠폰을 넣는
	 * R__seed_local.sql 을 전제로 하므로 그 뒤에 와야 한다 (예: R__seed_demo 는 seed_local 보다 앞이라 안 된다, project #12)
	 */
	@Test
	void 다른_local_시드는_R__seed_local_뒤에_실행된다() throws IOException {
		List<String> descriptions = names("classpath*:db/seed/local/R__*.sql").stream()
			// Flyway 는 설명의 '_' 를 공백으로 바꿔 비교한다
			.map(path -> path.substring(path.lastIndexOf("R__") + 3, path.length() - ".sql".length()).replace('_', ' '))
			.sorted()
			.toList();

		assertThat(descriptions).contains("seed local demo");
		assertThat(descriptions.getFirst()).isEqualTo("seed local");
	}

	/** 클래스패스 리소스의 db/ 아래 상대 경로 (예: db/migration/V1__init.sql) */
	private static List<String> names(String pattern) throws IOException {
		return Arrays.stream(RESOLVER.getResources(pattern)).map(FlywayLocationTest::relativePath).toList();
	}

	private static String relativePath(Resource resource) {
		try {
			String url = resource.getURL().toString();
			return url.substring(url.indexOf("db/"));
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
