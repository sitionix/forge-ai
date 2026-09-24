package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeai.infrastructure.agentclient.dto.McpAvailablePageInbound;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpAvailableCatalogAdapterTest {
    @Test
    void mapsTypedAgentResponseThroughExistingExecutor() {
        McpAvailableAgentFeignClient client = (search, cursor, limit) -> {
            assertThat(search).isEqualTo("search");
            assertThat(cursor).isEqualTo("next");
            assertThat(limit).isEqualTo(20);
            return new McpAvailablePageInbound(List.of(new McpAvailablePageInbound.McpAvailableServerInbound(
                    "io.example/search", "Search", "Find records", "1.0.0", "https://example.org/mcp")), "after");
        };
        var properties = new ForgeAgentClientProperties();
        var adapter = new McpAvailableCatalogAdapter(client, new ForgeAgentClientCallExecutor(properties));

        var page = adapter.list("search", "next", 20);

        assertThat(page.servers()).extracting(server -> server.name()).containsExactly("io.example/search");
        assertThat(page.nextCursor()).isEqualTo("after");
    }
}
