package com.sitionix.forgeagent.domain.model;

import java.util.List;

public record McpAvailablePage(List<McpAvailableServer> servers, String nextCursor) {}
