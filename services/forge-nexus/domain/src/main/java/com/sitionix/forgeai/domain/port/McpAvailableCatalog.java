package com.sitionix.forgeai.domain.port;

import com.sitionix.forgeai.domain.model.mcp.McpAvailablePage;

public interface McpAvailableCatalog {
    McpAvailablePage list(String search, String cursor, int limit);
}
