package com.sitionix.forgeai.application.agentproxy;

import com.sitionix.forgeai.domain.model.mcp.McpAvailablePage;
import com.sitionix.forgeai.domain.port.McpAvailableCatalog;
import com.sitionix.forgeai.domain.usecase.AgentMcpAvailableUseCase;
import org.springframework.stereotype.Service;

@Service

public class AgentMcpAvailableService implements AgentMcpAvailableUseCase {
    private final McpAvailableCatalog catalog;

    public AgentMcpAvailableService(McpAvailableCatalog catalog) {
        this.catalog = catalog;
    }

    public McpAvailablePage list(String search, String cursor, int limit) {
        return catalog.list(search, cursor, limit);
    }
}
