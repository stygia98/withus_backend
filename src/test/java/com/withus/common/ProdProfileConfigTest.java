package com.withus.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * 운영 프로필(application-prod.yml)의 구현체 선택 값을 고정한다 (PRD 8.5 "프로필 전환만으로 운영 배포").
 * MessageSender·FileStorage 구현체는 @ConditionalOnProperty 에 기본값(matchIfMissing)이 없어서, 값이 빠지거나 바뀌면
 * 운영에서 빈이 없어 기동이 실패하거나 Mailpit·로컬 디스크로 잘못 나간다. 운영 DB 에 시드가 섞이지 않는지도 함께 본다(이슈 #46).
 * DB·Spring 컨텍스트 없이 돈다
 */
class ProdProfileConfigTest {

	private static Properties prod() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application-prod.yml"));
		return yaml.getObject();
	}

	@Test
	void 운영은_SES_S3_SMS_Mock_구현체를_고른다() {
		Properties prod = prod();

		assertThat(prod.getProperty("withus.mail.type")).isEqualTo("ses");
		assertThat(prod.getProperty("withus.storage.type")).isEqualTo("s3");
		assertThat(prod.getProperty("withus.sms.type")).isEqualTo("mock");
	}

	@Test
	void 운영_Flyway_location_에는_local_시드가_없다() {
		assertThat(prod().getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
	}

	@Test
	void 운영_쿠키는_Secure_이고_F12_안내는_기본으로_꺼져_있다() {
		Properties prod = prod();

		assertThat(prod.getProperty("withus.cookie.secure")).isEqualTo("true");
		assertThat(prod.getProperty("withus.scheduler.consent-notice.enabled"))
			.isEqualTo("${WITHUS_SCHEDULER_CONSENT_NOTICE_ENABLED:false}");
	}
}
