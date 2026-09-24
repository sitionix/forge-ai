package com.sitionix.forgeagent.infrastructure.local.mcp.registry;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "forge.mcp.enabled", havingValue = "true")
@EnableCaching
class McpRegistryHttpClientConfiguration {
    @Bean
    CacheManager mcpRegistryCacheManager() {
        return cacheManager(Ticker.systemTicker());
    }

    CaffeineCacheManager cacheManager(Ticker ticker) {
        CaffeineCacheManager manager = new CaffeineCacheManager("mcpRegistryPages");
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .ticker(ticker));
        return manager;
    }

    @Bean
    McpRegistryHttpClient mcpRegistryHttpClient(
            RestClient.Builder builder,
            @Value("${forge.mcp.registry.base-url}") String baseUrl,
            @Value("${forge.mcp.registry.connect-timeout}") Duration connectTimeout,
            @Value("${forge.mcp.registry.read-timeout}") Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        RestClient restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build().createClient(McpRegistryHttpClient.class);
    }
}
