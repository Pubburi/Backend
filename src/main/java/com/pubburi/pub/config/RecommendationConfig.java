package com.pubburi.pub.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.recommendation.BaselineGateway;
import com.pubburi.pub.recommendation.BaselineHttpClient;
import com.pubburi.pub.recommendation.SearchPhraseGateway;
import com.pubburi.pub.recommendation.SearchPhraseHttpClient;
import com.pubburi.pub.recommendation.ContentGateway;
import com.pubburi.pub.recommendation.ContentHttpClient;
import com.pubburi.pub.recommendation.ContentCatalogSource;
import com.pubburi.pub.recommendation.PinnedContentCatalog;

@Configuration
public class RecommendationConfig {
	@Bean ContentCatalogSource contentCatalogSource(@Value("${pubburi.recommendation.content-catalog-path:}") String path,
			@Value("${pubburi.recommendation.content-catalog-sha256:}") String sha,
			@Value("${pubburi.recommendation.content-family-browse-demo-enabled:false}") boolean familyBrowseDemoEnabled,ObjectMapper mapper){
		return new PinnedContentCatalog(path,sha,mapper,java.time.Clock.systemUTC(),familyBrowseDemoEnabled);
	}
	@Bean ContentGateway contentGateway(@Value("${pubburi.recommendation.base-url}") String baseUrl,ObjectMapper mapper){
		return new ContentHttpClient(URI.create(baseUrl),Duration.ofMillis(200),Duration.ofSeconds(3),mapper);
	}
	@Bean
	BaselineGateway baselineGateway(@Value("${pubburi.recommendation.base-url}") String baseUrl, ObjectMapper mapper) {
		return new BaselineHttpClient(URI.create(baseUrl), Duration.ofMillis(200), Duration.ofMillis(700), mapper);
	}

	@Bean
	SearchPhraseGateway searchPhraseGateway(@Value("${pubburi.recommendation.base-url}") String baseUrl, ObjectMapper mapper) {
		return new SearchPhraseHttpClient(URI.create(baseUrl), Duration.ofMillis(200), Duration.ofMillis(700), mapper);
	}
}
