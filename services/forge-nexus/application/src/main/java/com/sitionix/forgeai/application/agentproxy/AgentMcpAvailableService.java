package com.sitionix.forgeai.application.agentproxy;

import com.sitionix.forgeai.domain.model.mcp.McpAvailablePage;
import com.sitionix.forgeai.domain.port.McpAvailableCatalog;
import com.sitionix.forgeai.domain.usecase.AgentMcpAvailableUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "forge.mcp.enabled", havingValue = "true")
public class AgentMcpAvailableService implements AgentMcpAvailableUseCase {
    private final McpAvailableCatalog catalog;

    public AgentMcpAvailableService(McpAvailableCatalog catalog) {
        this.catalog = catalog;
    }

    public McpAvailablePage list(String search, String cursor, int limit) {
        if (limit < 1 || limit > 100 || (search != null && search.length() > 200)
                || (cursor != null && cursor.length() > 2048)) {
            throw new IllegalArgumentException("Invalid MCP catalog request");
        }
        return catalog.list(search, cursor, limit);
    }
}
