package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeActiveProfileClientProperties.class)
class KnowledgeActiveProfileHttpClientConfiguration {

    @Bean
    KnowledgeActiveProfileHttpClient knowledgeActiveProfileHttpClient(
            final KnowledgeActiveProfileClientProperties properties,
            final RestClient.Builder restClientBuilder
    ) {
        final RestClient restClient = restClientBuilder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(this.requestFactory(properties))
                .build();

        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(KnowledgeActiveProfileHttpClient.class);
    }

    private JdkClientHttpRequestFactory requestFactory(final KnowledgeActiveProfileClientProperties properties) {
        final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        final JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return requestFactory;
    }
}
