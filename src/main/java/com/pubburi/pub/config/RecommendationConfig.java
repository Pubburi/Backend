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
import com.pubburi.pub.recommendation.ContentHttpClient;
import com.pubburi.pub.recommendation.ContentCatalogSource;
import com.pubburi.pub.recommendation.PinnedContentCatalog;
import com.pubburi.pub.recommendation.PinnedReviewedLocalCatalog;

@Configuration
public class RecommendationConfig {
	@Bean ContentCatalogSource contentCatalogSource(@Value("${pubburi.recommendation.content-catalog-path:}") String path,
			@Value("${pubburi.recommendation.content-catalog-sha256:}") String sha,
			@Value("${pubburi.recommendation.content-family-browse-demo-enabled:false}") boolean familyBrowseDemoEnabled,
            @Value("${pubburi.recommendation.reviewed-catalog-path:}") String reviewedPath,
            @Value("${pubburi.recommendation.reviewed-catalog-sha256:}") String reviewedSha,
            @Value("${pubburi.recommendation.reviewed-mapping-path:}") String mappingPath,
            @Value("${pubburi.recommendation.reviewed-mapping-sha256:}") String mappingSha,ObjectMapper mapper){
        boolean reviewed=java.util.stream.Stream.of(reviewedPath,reviewedSha,mappingPath,mappingSha).anyMatch(value->value!=null && !value.isBlank());
        if(reviewed){
            if(java.util.stream.Stream.of(path,sha).anyMatch(value->value!=null && !value.isBlank()) || familyBrowseDemoEnabled)return java.util.Optional::empty;
            return new PinnedReviewedLocalCatalog(reviewedPath,reviewedSha,mappingPath,mappingSha,mapper,java.time.Clock.systemUTC());
        }
		return new PinnedContentCatalog(path,sha,mapper,java.time.Clock.systemUTC(),familyBrowseDemoEnabled);
	}
	@Bean ContentHttpClient contentGateway(@Value("${pubburi.recommendation.base-url}") String baseUrl,ObjectMapper mapper){
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
