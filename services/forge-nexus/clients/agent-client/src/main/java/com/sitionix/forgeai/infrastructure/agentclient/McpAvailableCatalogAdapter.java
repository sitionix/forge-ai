package com.sitionix.forgeai.infrastructure.agentclient;

import com.sitionix.forgeai.domain.model.mcp.McpAvailablePage;
import com.sitionix.forgeai.domain.port.McpAvailableCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "forge.mcp.enabled", havingValue = "true")
public class McpAvailableCatalogAdapter implements McpAvailableCatalog {
    private final ForgeAgentHttpClient client;
    private final ForgeAgentClientCallExecutor executor;
    private final McpClientMapper mapper;

    public McpAvailableCatalogAdapter(ForgeAgentHttpClient client, ForgeAgentClientCallExecutor executor,
                                      McpClientMapper mapper) {
        this.client = client;
        this.executor = executor;
        this.mapper = mapper;
    }

    @Override
    public McpAvailablePage list(String search, String cursor, int limit) {
        return mapper.toDomain(executor.execute(() -> client.listAvailableMcp(search, cursor, limit)));
    }
}
