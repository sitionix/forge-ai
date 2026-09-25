package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeai.infrastructure.agentclient.dto.McpAvailablePageInbound;
import com.sitionix.forgeai.infrastructure.agentclient.dto.McpProbeInboundResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpClientMapperTest {
    private final McpClientMapper mapper = new McpClientMapper();

    @Test void mapsTypedProbeAndRejectsMalformedUpstream() {
        var report = mapper.toDomain(new McpProbeInboundResponse("2025-11-25",
                List.of(new McpProbeInboundResponse.Tool("read", "Read", "sha256:one"))));
        assertThat(report.protocolVersion()).isEqualTo("2025-11-25");
        assertThat(report.tools().getFirst().schemaFingerprint()).isEqualTo("sha256:one");
        assertThatThrownBy(() -> mapper.toDomain(new McpProbeInboundResponse("2025-11-25", null)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Invalid MCP upstream response");
        assertThatThrownBy(() -> mapper.toTools(List.of(new McpProbeInboundResponse.Tool("", null, "sha256:one"))))
                .isInstanceOf(IllegalStateException.class).hasMessage("Invalid MCP upstream response");
    }

    @Test
    void mapsAvailablePageAndKeepsTemplateEndpoint() {
        var inbound = new McpAvailablePageInbound(List.of(
                new McpAvailablePageInbound.McpAvailableServerInbound(
                        "io.example/search", "Search", "Find records", "1.0.0",
                        "https://{tenant_id}.example.org/mcp")), "next");

        var page = mapper.toDomain(inbound);

        assertThat(page.nextCursor()).isEqualTo("next");
        assertThat(page.servers()).hasSize(1);
        assertThat(page.servers().getFirst().name()).isEqualTo("io.example/search");
        assertThat(page.servers().getFirst().title()).isEqualTo("Search");
        assertThat(page.servers().getFirst().description()).isEqualTo("Find records");
        assertThat(page.servers().getFirst().version()).isEqualTo("1.0.0");
        assertThat(page.servers().getFirst().endpoint())
                .isEqualTo("https://{tenant_id}.example.org/mcp");
    }

    @Test
    void rejectsMissingAvailablePageStructureAsInvalidUpstream() {
        assertThatThrownBy(() -> mapper.toDomain((McpAvailablePageInbound) null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid MCP upstream response");
        assertThatThrownBy(() -> mapper.toDomain(new McpAvailablePageInbound(null, "next")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid MCP upstream response");
        assertThatThrownBy(() -> mapper.toDomain(new McpAvailablePageInbound(
                java.util.Arrays.asList((McpAvailablePageInbound.McpAvailableServerInbound) null), "next")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid MCP upstream response");
    }
}
