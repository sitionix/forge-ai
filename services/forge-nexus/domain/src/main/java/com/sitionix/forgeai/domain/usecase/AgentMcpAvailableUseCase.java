package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.mcp.McpAvailablePage;

public interface AgentMcpAvailableUseCase {
    McpAvailablePage list(String search, String cursor, int limit);
}
