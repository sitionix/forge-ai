package com.sitionix.forgeagent.infrastructure.local.mcp.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.port.McpAuthenticationDiscovery;
import com.sitionix.forgeagent.infrastructure.local.mcp.McpEndpointPolicy;
import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties({McpOAuthDiscoveryProperties.class,McpOAuthRegistrationProperties.class})
public class McpOAuthDiscoveryConfiguration {
    @Bean McpOAuthMetadataHttpClient mcpOAuthMetadataHttpClient(@Qualifier("mcpOAuthHttpClient") HttpClient http,
            McpOAuthProperties properties,McpOAuthDiscoveryProperties discovery,ObjectMapper mapper,
            @Value("${forge.mcp.probe.max-response-bytes:1048576}") int maxBytes,
            @Value("${forge.mcp.probe.allowed-private-endpoints:}") String allowed) {
        if(discovery.discoveryTimeout().plus(properties.readTimeout()).compareTo(java.time.Duration.ofSeconds(25))>0)
            throw new IllegalArgumentException("OAuth preparation and socket wait exceed the Nexus management HTTP budget");
        var policy=new McpEndpointPolicy(Arrays.stream(allowed.split(",")).map(String::strip).filter(s->!s.isEmpty()).collect(Collectors.toUnmodifiableSet()));
        return new McpOAuthMetadataHttpClient(http,policy,properties.readTimeout(),maxBytes,mapper);
    }
    @Bean com.sitionix.forgeagent.domain.port.McpOAuthClientRegistrationProvider mcpOAuthClientRegistrationProvider(McpOAuthMetadataHttpClient http,McpOAuthRegistrationProperties clients,McpOAuthProperties properties) {
        return new SpringMcpOAuthClientRegistrationProvider(http,clients,properties.callbackUri());
    }
    @Bean McpAuthenticationDiscovery mcpAuthenticationDiscovery(McpOAuthMetadataHttpClient http,McpOAuthRegistrationProperties clients) {
        return new SpringMcpAuthenticationDiscovery(http,clients.clients().stream().map(McpOAuthRegistrationProperties.Client::issuer).collect(Collectors.toUnmodifiableSet()));
    }
}
