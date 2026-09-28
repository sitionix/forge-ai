package com.sitionix.forgeai.infrastructure.agentclient;

import java.net.http.HttpClient;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
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
  AgentServiceCredential agentServiceCredential(
      @Value("${forge.mcp.agent-service-credential-file}") final Path file,
      final ForgeAgentClientProperties properties) {
    return new AgentServiceCredential(file, properties.getBaseUrl());
  }

  @Bean
  ForgeAgentHttpClient forgeAgentHttpClient(
      final ForgeAgentClientProperties properties, final RestClient.Builder restClientBuilder,
      final AgentServiceCredential credential) {
      restClientBuilder.requestInterceptor((request, body, execution) -> {
        request.getHeaders().set(org.springframework.http.HttpHeaders.AUTHORIZATION,
            credential.authorization());
        return execution.execute(request, body);
      });
    final RestClient restClient =
        restClientBuilder
            .baseUrl(properties.getBaseUrl().toString())
            .requestFactory(this.requestFactory(properties))
            .build();
    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
        .build()
        .createClient(ForgeAgentHttpClient.class);
  }

  @Bean
  ForgeAgentLogStreamingHttpClient forgeAgentLogStreamingHttpClient(
      final ForgeAgentClientProperties properties,
      final ForgeAgentClientCallExecutor callExecutor,
      final AgentServiceCredential credential) {
    final HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    return new ForgeAgentLogStreamingHttpClient(httpClient, properties, callExecutor, credential);
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
