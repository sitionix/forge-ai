package com.sitionix.forgeagent.infrastructure.local.mcp.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.infrastructure.local.mcp.McpEndpointPolicy;
import java.net.http.HttpClient;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpOAuthProperties.class)
public class McpOAuthHttpConfiguration {
    @Bean(destroyMethod = "close") HttpClient mcpOAuthHttpClient(McpOAuthProperties properties, ObjectProvider<SslBundles> bundles) {
        var builder = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY);
        if (!properties.sslBundle().isBlank()) {
            var available = bundles.getIfAvailable();
            if (available == null) throw new IllegalStateException("OAuth SSL bundle is unavailable");
            builder.sslContext(available.getBundle(properties.sslBundle()).createSslContext());
        }
        return builder.build();
    }

    @Bean McpOAuthClient mcpOAuthClient(McpOAuthProperties properties,
            @Qualifier("mcpOAuthHttpClient") HttpClient http,
            @Value("${forge.mcp.probe.allowed-private-endpoints:}") String privateEndpoints) {
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        var rest = new RestTemplate(List.of(new FormHttpMessageConverter(), SpringMcpOAuthClient.tokenConverter()));
        rest.setRequestFactory(factory);
        rest.setErrorHandler(new OAuth2ErrorResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
                return response.getStatusCode().is3xxRedirection() || super.hasError(response);
            }
            @Override public void handleError(org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
                if (response.getStatusCode().is3xxRedirection())
                    throw new org.springframework.security.oauth2.core.OAuth2AuthorizationException(
                            new org.springframework.security.oauth2.core.OAuth2Error("invalid_token_response"));
                // Spring delegates non-400 errors to its generic HTTP handler,
                // losing OAuth error codes in providers' JSON 401 responses.
                if (response.getStatusCode().value() == 401) {
                    org.springframework.security.oauth2.core.OAuth2Error error;
                    try {
                        error = new org.springframework.security.oauth2.core.http.converter.OAuth2ErrorHttpMessageConverter()
                                .read(org.springframework.security.oauth2.core.OAuth2Error.class, response);
                    } catch (org.springframework.http.converter.HttpMessageNotReadableException | IllegalArgumentException malformed) {
                        throw new org.springframework.security.oauth2.core.OAuth2AuthorizationException(
                                new org.springframework.security.oauth2.core.OAuth2Error("invalid_token_response"));
                    }
                    throw new org.springframework.security.oauth2.core.OAuth2AuthorizationException(error);
                }
                super.handleError(response);
            }
        });
        Set<String> allowances = Arrays.stream(privateEndpoints.split(",")).map(String::strip)
                .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        return new SpringMcpOAuthClient(rest, new McpEndpointPolicy(allowances), properties.callbackUri());
    }

    @Bean McpOAuthCredentialCipher mcpOAuthCredentialCipher(McpCredentialCipher cipher, ObjectMapper mapper) {
        return new JacksonMcpOAuthCredentialCipher(cipher, mapper);
    }
}
