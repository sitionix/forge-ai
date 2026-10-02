package com.sitionix.forgeai.infrastructure.agentclient;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;
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
  ForgeAgentHttpClient forgeAgentHttpClient(
      final ForgeAgentClientProperties properties, final RestClient.Builder restClientBuilder,
      @Value("${forge.mcp.catalog.agent-read-timeout:55s}") final Duration catalogReadTimeout) {
    final RestClient restClient =
        restClientBuilder
            .baseUrl(properties.getBaseUrl().toString())
            .requestFactory(this.requestFactory(properties, catalogReadTimeout))
            .build();
    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
        .build()
        .createClient(ForgeAgentHttpClient.class);
  }

  @Bean
  ForgeAgentLogStreamingHttpClient forgeAgentLogStreamingHttpClient(
      final ForgeAgentClientProperties properties,
      final ForgeAgentClientCallExecutor callExecutor) {
    final HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    return new ForgeAgentLogStreamingHttpClient(httpClient, properties, callExecutor);
  }

  private ClientHttpRequestFactory requestFactory(
      final ForgeAgentClientProperties properties, final Duration catalogReadTimeout) {
    final HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    final JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.getReadTimeout());
    final JdkClientHttpRequestFactory catalogFactory = new JdkClientHttpRequestFactory(httpClient);
    catalogFactory.setReadTimeout(catalogReadTimeout);
    return (uri, method) -> {
      final boolean catalog = method == HttpMethod.GET
          && uri.getPath().endsWith("/api/v1/integrations/mcp/available");
      return (catalog ? catalogFactory : requestFactory).createRequest(uri, method);
    };
  }

}
