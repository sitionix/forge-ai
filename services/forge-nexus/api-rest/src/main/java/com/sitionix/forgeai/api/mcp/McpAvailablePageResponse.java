package com.sitionix.forgeai.api.mcp;

import com.sitionix.forgeai.domain.model.mcp.McpAvailablePage;
import java.util.List;

public record McpAvailablePageResponse(List<Server> servers, String nextCursor) {
    public static McpAvailablePageResponse from(McpAvailablePage page) {
        return new McpAvailablePageResponse(page.servers().stream()
                .map(server -> new Server(server.name(), server.title(), server.description(),
                        server.version(), server.endpoint()))
                .toList(), page.nextCursor());
    }

    public record Server(String name, String title, String description,
                         String version, String endpoint) {}
}
