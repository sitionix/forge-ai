package com.sitionix.forgeagent.application.mcp;

import com.sitionix.forgeagent.domain.model.McpAvailablePage;
import com.sitionix.forgeagent.domain.port.McpRegistryCatalog;
import java.util.Objects;

public class McpAvailableService {
    private final McpRegistryCatalog catalog;

    public McpAvailableService(McpRegistryCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog);
    }

    public McpAvailablePage list(String search, String cursor, int limit) {
        if (limit < 1 || limit > 100 || (search != null && search.length() > 200)
                || (cursor != null && cursor.length() > 2048)) {
            throw new IllegalArgumentException("Invalid MCP catalog request");
        }
        return catalog.list(search, cursor, limit);
    }
}
