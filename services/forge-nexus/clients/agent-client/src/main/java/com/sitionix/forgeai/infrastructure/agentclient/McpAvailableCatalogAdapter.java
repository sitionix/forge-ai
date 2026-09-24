package com.sitionix.forgeai.infrastructure.agentclient;

import com.sitionix.forgeai.domain.model.mcp.McpAvailablePage;
import com.sitionix.forgeai.domain.model.mcp.McpAvailableServer;
import com.sitionix.forgeai.domain.port.McpAvailableCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "forge.mcp.enabled", havingValue = "true")
public class McpAvailableCatalogAdapter implements McpAvailableCatalog {
    private final McpAvailableAgentFeignClient client;
    private final ForgeAgentClientCallExecutor executor;

    public McpAvailableCatalogAdapter(McpAvailableAgentFeignClient client, ForgeAgentClientCallExecutor executor) {
        this.client = client;
        this.executor = executor;
    }

    @Override
    public McpAvailablePage list(String search, String cursor, int limit) {
        var response = executor.execute(() -> client.list(search, cursor, limit));
        if (response == null || response.servers() == null) {
            throw new org.springframework.web.client.RestClientException("Invalid Agent MCP response");
        }
        return new McpAvailablePage(response.servers().stream()
                .map(server -> new McpAvailableServer(server.name(), server.title(), server.description(),
                        server.version(), server.endpoint()))
                .toList(), response.nextCursor());
    }
}
