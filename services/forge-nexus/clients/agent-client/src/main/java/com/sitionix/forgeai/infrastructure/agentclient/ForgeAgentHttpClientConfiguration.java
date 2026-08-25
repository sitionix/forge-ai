package com.sitionix.forgeai.infrastructure.agentclient;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ForgeAgentClientProperties.class)
class ForgeAgentHttpClientConfiguration {

  @Bean
  RestClient forgeAgentRestClient(
      final ForgeAgentClientProperties properties, final RestClient.Builder restClientBuilder) {
    return restClientBuilder
        .baseUrl(properties.getBaseUrl().toString())
        .requestFactory(this.requestFactory(properties))
        .build();
  }

  @Bean("forgeAgentLogStreamingRestClient")
  RestClient forgeAgentLogStreamingRestClient(
      final ForgeAgentClientProperties properties, final RestClient.Builder restClientBuilder) {
    final HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    return restClientBuilder
        .baseUrl(properties.getBaseUrl().toString())
        .requestFactory(new JdkClientHttpRequestFactory(httpClient))
        .build();
  }

  @Bean
  ForgeAgentHttpClient forgeAgentHttpClient(final RestClient forgeAgentRestClient) {
    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(forgeAgentRestClient))
        .build()
        .createClient(ForgeAgentHttpClient.class);
  }

  private JdkClientHttpRequestFactory requestFactory(final ForgeAgentClientProperties properties) {
    final HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    final JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.getReadTimeout());
    return requestFactory;
  }
}
