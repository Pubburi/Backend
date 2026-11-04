package com.pubburi.pub.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pubburi.pub.search.ProductSearchGateway;
import com.pubburi.pub.search.ProductSearchHttpClient;

@Configuration
public class ProductSearchConfig {
    @Bean ProductSearchGateway productSearchGateway(@Value("${pubburi.search.enabled:false}") boolean enabled,
            @Value("${pubburi.search.endpoint:http://127.0.0.1:9200}") String endpoint,
            @Value("${pubburi.search.index:pubburi-products-v1-live}") String index,ObjectMapper mapper) {
        return enabled?new ProductSearchHttpClient(URI.create(endpoint),index,Duration.ofMillis(200),Duration.ofMillis(700),mapper):ProductSearchGateway.disabled();
    }
}
