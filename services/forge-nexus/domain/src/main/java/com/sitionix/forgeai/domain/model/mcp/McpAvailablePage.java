package com.sitionix.forgeai.domain.model.mcp;

import java.util.List;

public record McpAvailablePage(List<McpAvailableServer> servers, String nextCursor) {}
