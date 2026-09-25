package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.McpAllowedTool;
import com.sitionix.forgeagent.domain.model.McpExecutionSelection;
import com.sitionix.forgeagent.domain.model.McpRuntimeLaunchGrants;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CodexMcpConfigurationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CodexMcpConfiguration configuration = new CodexMcpConfiguration();
    private final UUID connectionId = UUID.fromString("01234567-89ab-4cde-8012-3456789abcde");

    @Test void emptyLaunchGrantsDoNotBreakOrdinaryExecution() {
        assertThat(new McpRuntimeLaunchGrants(Map.of()).isEmpty()).isTrue();
    }

    @Test void addsOnlyApprovedToolsAndEnvironmentReference() {
        var config = json.createObjectNode();
        var entry = new McpExecutionSelection.Entry("forge_0123456789ab4cde80123456789abcde",
                connectionId, "Read-only search", Set.of(new McpAllowedTool("search", "sha256:fingerprint")));
        configuration.apply(config, new McpExecutionSelection(List.of(entry), List.of()),
                URI.create("http://127.0.0.1:18345"), Path.of("/tmp/forge-work"));

        var server = config.path("mcp_servers").path(entry.alias());
        assertThat(server.path("url").asText()).isEqualTo(
                "http://127.0.0.1:18345/internal/mcp/connections/" + connectionId);
        assertThat(server.path("bearer_token_env_var").asText()).isEqualTo(
                "FORGE_MCP_GRANT_0123456789AB4CDE80123456789ABCDE");
        assertThat(server.path("enabled_tools").get(0).asText()).isEqualTo("search");
        assertThat(server.path("tools").path("search").path("approval_mode").asText()).isEqualTo("approve");
        assertThat(config.path("projects").path("/tmp/forge-work").path("trust_level").asText())
                .isEqualTo("untrusted");
        assertThat(config.path("sandbox_workspace_write.network_access").asBoolean()).isFalse();
        assertThat(config.toString()).doesNotContain("synthetic-grant");
    }

    @Test void rejectsNonLoopbackGateway() {
        assertThatThrownBy(() -> configuration.apply(json.createObjectNode(),
                new McpExecutionSelection(List.of(), List.of()), URI.create("http://example.org:8080"),
                Path.of("/tmp/work"))).isInstanceOf(CodexTransportException.class);
    }
}
