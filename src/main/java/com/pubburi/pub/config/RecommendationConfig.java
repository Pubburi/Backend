package com.pubburi.pub.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.recommendation.BaselineGateway;
import com.pubburi.pub.recommendation.BaselineHttpClient;

@Configuration
public class RecommendationConfig {
	@Bean
	BaselineGateway baselineGateway(@Value("${pubburi.recommendation.base-url}") String baseUrl, ObjectMapper mapper) {
		return new BaselineHttpClient(URI.create(baseUrl), Duration.ofMillis(200), Duration.ofMillis(700), mapper);
	}
}
