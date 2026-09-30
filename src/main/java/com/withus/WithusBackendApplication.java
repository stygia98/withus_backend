package com.withus;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class WithusBackendApplication {

	public static void main(String[] args) {
		// JVM 타임존 고정 (PRD 2.4)
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
		SpringApplication.run(WithusBackendApplication.class, args);
	}

}
