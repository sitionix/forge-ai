package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.port.CorrelationIdProvider;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    KnowledgeActiveProfileResponseValidator knowledgeActiveProfileResponseValidator() {
        return new KnowledgeActiveProfileResponseValidator();
    }

    @Bean
    KnowledgeActiveProfileClientFailures knowledgeActiveProfileClientFailures(
            final CorrelationIdProvider correlationIdProvider
    ) {
        return new KnowledgeActiveProfileClientFailures(correlationIdProvider);
    }

    @Bean
    KnowledgeActiveProfileStatusHandler knowledgeActiveProfileStatusHandler(
            final KnowledgeActiveProfileJson json,
            final KnowledgeActiveProfileClientFailures failures
    ) {
        return new KnowledgeActiveProfileStatusHandler(json.objectMapper(), failures);
    }

    @Bean
    KnowledgeActiveProfileHttpClient knowledgeActiveProfileHttpClient(
            final KnowledgeActiveProfileClientProperties properties,
            final KnowledgeActiveProfileJson json,
            final KnowledgeActiveProfileStatusHandler statusHandler,
            final CorrelationIdProvider correlationIdProvider
    ) {
        properties.validate();
        final RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(this.requestFactory(properties))
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().set(KnowledgeActiveProfileHttpHeaders.CORRELATION_ID, correlationIdProvider.currentOrCreate());
                    return execution.execute(request, body);
                })
                .messageConverters(converters -> {
                    converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                    converters.add(new MappingJackson2HttpMessageConverter(json.objectMapper()));
                })
                .defaultStatusHandler(
                        status -> status.is3xxRedirection() || status.isError(),
                        statusHandler::handle
                )
                .build();

        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(KnowledgeActiveProfileHttpClient.class);
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
