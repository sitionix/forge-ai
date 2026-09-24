package com.sitionix.forgeai.infrastructure.agentclient;

import feign.RequestInterceptor;
import feign.Request;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;

class McpAvailableFeignConfiguration {
    @Bean
    Request.Options mcpAvailableOptions(ForgeAgentClientProperties properties) {
        return new Request.Options(properties.getConnectTimeout(), properties.getReadTimeout(), false);
    }

    @Bean
    RequestInterceptor mcpAvailableCredential(ObjectProvider<AgentServiceCredential> credentials) {
        return request -> request.header("Authorization", credentials.getObject().authorization());
    }
}
