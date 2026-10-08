package com.withus.campaign.config;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * withus.storage.type=local 일 때만 /files/** 로 업로드 이미지를 정적 서빙한다.
 * prod(S3)는 버킷 URL을 직접 쓰므로 이 설정이 필요 없다 (W4)
 */
@Configuration
@ConditionalOnProperty(prefix = "withus.storage", name = "type", havingValue = "local")
public class LocalFileWebConfig implements WebMvcConfigurer {

	private final String localPath;

	public LocalFileWebConfig(@Value("${withus.storage.local-path}") String localPath) {
		this.localPath = localPath;
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		String location = Path.of(localPath).toAbsolutePath().normalize().toUri().toString();
		if (!location.endsWith("/")) {
			location += "/";
		}
		registry.addResourceHandler("/files/**").addResourceLocations(location);
	}
}
