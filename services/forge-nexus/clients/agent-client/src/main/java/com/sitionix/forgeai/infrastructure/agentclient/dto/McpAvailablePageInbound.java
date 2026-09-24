package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.List;

public record McpAvailablePageInbound(List<McpAvailableServerInbound> servers, String nextCursor) {
    public record McpAvailableServerInbound(String name, String title, String description,
                                            String version, String endpoint) {}
}
