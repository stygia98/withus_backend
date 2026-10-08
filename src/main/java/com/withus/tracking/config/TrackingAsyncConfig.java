package com.withus.tracking.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 추적 이벤트 저장용 비동기 실행기 (PRD 8.1: 이벤트 저장은 비동기, 응답은 항상 정상).
 * 큐가 가득 차면 예외 없이 이벤트를 버린다. 수신자의 응답을 늦추는 것보다 이벤트 1건을 잃는 편이 낫다.
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(TrackingProperties.class)
public class TrackingAsyncConfig {

	public static final String EXECUTOR = "trackingEventExecutor";

	private static final Logger log = LoggerFactory.getLogger(TrackingAsyncConfig.class);

	@Bean(EXECUTOR)
	public ThreadPoolTaskExecutor trackingEventExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("tracking-");
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(1000);
		executor.setRejectedExecutionHandler((task, pool) ->
			log.warn("추적 이벤트 큐가 가득 차 이벤트를 버립니다. queue={}", pool.getQueue().size()));
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(5);
		return executor;
	}
}
