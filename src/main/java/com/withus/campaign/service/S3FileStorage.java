package com.withus.campaign.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * withus.storage.type=s3 일 때만 활성화. 버킷은 환경변수 S3_BUCKET.
 * 객체는 images/ 아래에만 둔다 — "S3 버킷은 이미지 경로만 공개 읽기" (PRD 8.5)를 이 접두어에 거는 버킷 정책으로 지킨다.
 * 공개 URL은 S3 공개 읽기 URL을 그대로 돌려줘 백엔드를 거치지 않는다 (PL 결정 A, 이슈 #78)
 */
@Component
@ConditionalOnProperty(prefix = "withus.storage", name = "type", havingValue = "s3")
public class S3FileStorage implements FileStorage {

	static final String PREFIX = "images/";

	private final S3Client s3;
	private final String bucket;

	public S3FileStorage(S3Client s3, @Value("${withus.storage.s3-bucket}") String bucket) {
		if (bucket == null || bucket.isBlank()) {
			throw new IllegalStateException("withus.storage.type=s3 이면 S3_BUCKET 환경변수가 필요합니다.");
		}
		this.s3 = s3;
		this.bucket = bucket;
	}

	@Override
	public String store(String originalFilename, InputStream in, String contentType) {
		String key = FileStorage.newKey(originalFilename);
		try (in) {
			// ponytail: 통째로 메모리에 읽는다(길이를 모르는 스트림 → 단일 PUT). 이미지 5MB 제한(FileController)이라 충분, 큰 파일을 받게 되면 멀티파트로
			byte[] data = in.readAllBytes();
			s3.putObject(PutObjectRequest.builder().bucket(bucket).key(PREFIX + key).contentType(contentType).build(),
				RequestBody.fromBytes(data));
		} catch (IOException e) {
			throw new UncheckedIOException("파일 저장에 실패했습니다: " + key, e);
		}
		return key;
	}

	@Override
	public String publicUrl(String key) {
		return s3.utilities().getUrl(b -> b.bucket(bucket).key(PREFIX + key)).toExternalForm();
	}

	/** 자격증명은 DefaultCredentialsProvider(EC2 IAM 역할 우선), 리전은 AWS_REGION. 키는 코드·설정에 두지 않는다 */
	@Configuration
	@ConditionalOnProperty(prefix = "withus.storage", name = "type", havingValue = "s3")
	static class S3ClientConfig {

		@Bean(destroyMethod = "close")
		S3Client s3Client() {
			return S3Client.builder()
				.credentialsProvider(DefaultCredentialsProvider.builder().build())
				.overrideConfiguration(ClientOverrideConfiguration.builder()
					.apiCallTimeout(Duration.ofSeconds(60)).build())
				.build();
		}
	}
}
