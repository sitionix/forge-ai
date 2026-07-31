package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.ClientHttpRequestInitializer;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeActiveProfileClientProperties.class)
class KnowledgeActiveProfileHttpClientConfiguration {

    @Bean
    KnowledgeActiveProfileJson knowledgeActiveProfileJson(final ObjectMapper objectMapper) {
        return new KnowledgeActiveProfileJson(objectMapper);
    }

    @Bean
    KnowledgeClientCallExecutor knowledgeClientCallExecutor(final KnowledgeActiveProfileJson json) {
        return new KnowledgeClientCallExecutor(json);
    }

    @Bean
    KnowledgeActiveProfileHttpClient knowledgeActiveProfileHttpClient(
            final KnowledgeActiveProfileClientProperties properties,
            final KnowledgeActiveProfileJson json,
            final ObjectProvider<ClientHttpRequestInitializer> initializers
    ) {
        properties.validate();
        final RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(this.requestFactory(properties))
                .requestInitializers(configured -> initializers.orderedStream().forEach(configured::add))
                .messageConverters(converters -> {
                    converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                    converters.add(new MappingJackson2HttpMessageConverter(json.objectMapper()));
                })
                .defaultStatusHandler(
                        status -> status.value() != 200,
                        (request, response) -> this.handleStatus(response)
                )
                .build();

        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(KnowledgeActiveProfileHttpClient.class);
    }

    private void handleStatus(final ClientHttpResponse response) throws IOException {
        throw new KnowledgeClientHttpStatusException(
                response.getStatusCode().value(),
                new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)
        );
    }

    private JdkClientHttpRequestFactory requestFactory(final KnowledgeActiveProfileClientProperties properties) {
        final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        final JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return requestFactory;
    }
}
