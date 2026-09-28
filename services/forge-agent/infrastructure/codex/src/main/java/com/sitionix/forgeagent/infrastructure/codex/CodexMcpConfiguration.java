package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.domain.model.McpExecutionSelection;
import java.net.URI;
import java.nio.file.Path;
import java.util.Comparator;

/** Maps safe execution selection to Codex's native, invocation-scoped MCP config. */
final class CodexMcpConfiguration {
    void apply(ObjectNode config, McpExecutionSelection selection, URI gatewayBase, Path cwd) {
        if (config == null || selection == null || cwd == null || !cwd.isAbsolute()
                || !validGateway(gatewayBase)) {
            throw new CodexTransportException("Codex MCP configuration is unavailable");
        }
        config.putObject("projects").putObject(cwd.normalize().toString()).put("trust_level", "untrusted");
        config.put("sandbox_workspace_write.network_access", false);
        ObjectNode servers = config.putObject("mcp_servers");
        for (var entry : selection.entries()) {
            String expectedAlias = "forge_" + entry.connectionId().toString().replace("-", "");
            if (!expectedAlias.equals(entry.alias()) || entry.tools().isEmpty() || servers.has(entry.alias())) {
                throw new CodexTransportException("Codex MCP selection is invalid");
            }
            ObjectNode server = servers.putObject(entry.alias());
            server.put("url", gatewayBase.toString().replaceAll("/$", "")
                    + "/internal/mcp/connections/" + entry.connectionId());
            server.put("bearer_token_env_var", "FORGE_MCP_GRANT_" +
                    entry.connectionId().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT));
            ArrayNode enabled = server.putArray("enabled_tools");
            ObjectNode tools = server.putObject("tools");
            entry.tools().stream().sorted(Comparator.comparing(tool -> tool.name())).forEach(tool -> {
                enabled.add(tool.name());
                tools.putObject(tool.name()).put("approval_mode", "approve");
            });
        }
    }

    private static boolean validGateway(URI base) {
        return base != null && "http".equals(base.getScheme()) && base.getPort() > 0
                && ("127.0.0.1".equals(base.getHost()) || "::1".equals(base.getHost()))
                && base.getUserInfo() == null && (base.getPath().isEmpty() || "/".equals(base.getPath()))
                && base.getQuery() == null && base.getFragment() == null;
    }
}
