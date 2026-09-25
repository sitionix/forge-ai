package com.sitionix.forgeagent.application.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.model.McpAvailablePage;
import com.sitionix.forgeagent.domain.model.McpAvailableServer;
import com.sitionix.forgeagent.domain.port.McpRegistryCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpAvailableServiceTest {
    @Test
    void listsOneRegistryPageWithoutConnectingToAnyMcpServer() {
        McpRegistryCatalog catalog = (search, cursor, limit) -> new McpAvailablePage(
                List.of(new McpAvailableServer("io.example/search", "Search", "Search records",
                        "1.0.0", "https://example.org/mcp")), "next-page");
        McpAvailableService service = new McpAvailableService(catalog);

        McpAvailablePage page = service.list("search", null, 20);

        assertThat(page.servers()).extracting(McpAvailableServer::name)
                .containsExactly("io.example/search");
        assertThat(page.nextCursor()).isEqualTo("next-page");
    }

    @Test
    void rejectsUnboundedRegistryRequestsBeforeCallingTheCatalog() {
        McpRegistryCatalog catalog = (search, cursor, limit) -> {
            throw new AssertionError("Registry must not be called");
        };
        McpAvailableService service = new McpAvailableService(catalog);

        assertThatThrownBy(() -> service.list("x", null, 1001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("x".repeat(201), null, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
