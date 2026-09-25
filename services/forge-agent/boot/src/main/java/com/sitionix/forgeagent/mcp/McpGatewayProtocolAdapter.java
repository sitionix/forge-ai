package com.sitionix.forgeagent.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.McpAllowedTool;
import com.sitionix.forgeagent.domain.model.McpRuntimeGrant;
import com.sitionix.forgeagent.domain.model.McpToolCallResult;
import com.sitionix.forgeagent.domain.port.McpGatewayRuntime;
import com.sitionix.forgeagent.infrastructure.local.mcp.gateway.SdkMcpGatewayToolView;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import reactor.core.publisher.Mono;

/** Narrow Spring/SDK bridge. It never invokes the SDK parser that logs raw request JSON. */
public final class McpGatewayProtocolAdapter {
    private static final Set<String> METHODS = Set.of("initialize", "tools/list", "tools/call");
    private static final Set<String> VERSIONS = Set.of("2025-03-26", "2025-06-18", "2025-11-25");
    private final McpGatewayRuntime runtime;
    private final SdkMcpGatewayToolView views;
    private final ObjectMapper json;
    private final JacksonMcpJsonMapper protocolJson;
    private final Duration timeout;

    public McpGatewayProtocolAdapter(McpGatewayRuntime runtime, SdkMcpGatewayToolView views,
                                     ObjectMapper json, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("Invalid MCP gateway timeout");
        this.runtime = runtime;
        this.views = views;
        this.json = json;
        this.protocolJson = new JacksonMcpJsonMapper(json.copy());
        this.timeout = timeout;
    }

    public ProtocolResponse process(McpRuntimeGrant grant, String token, byte[] body) {
        Object id = null;
        try {
            JsonNode root = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            if (root == null || !root.isObject() || !"2.0".equals(root.path("jsonrpc").asText())
                    || !root.path("method").isTextual()) return error(null, -32600, "Invalid MCP request");
            String method = root.path("method").asText();
            if (!root.has("id")) {
                if (!"notifications/initialized".equals(method)) return error(null, -32600, "Invalid MCP request");
                return new ProtocolResponse(202, new byte[0]);
            }
            JsonNode idNode = root.get("id");
            if (!idNode.isTextual() && !idNode.isIntegralNumber()) return error(null, -32600, "Invalid MCP request");
            id = idNode.isTextual() ? idNode.asText() : idNode.numberValue();
            if (!METHODS.contains(method)) return error(id, -32601, "MCP method unavailable");
            if (method.equals("initialize")) {
                JsonNode version = root.path("params").path("protocolVersion");
                if (!version.isTextual() || !VERSIONS.contains(version.asText()))
                    return error(id, -32602, "Unsupported MCP protocol version");
                // The SDK logs client metadata at INFO; pass only fixed, safe metadata to it.
                var params = (com.fasterxml.jackson.databind.node.ObjectNode) root.path("params");
                params.set("capabilities", json.createObjectNode());
                var client = json.createObjectNode().put("name", "forge-runtime").put("version", "1");
                params.set("clientInfo", client);
            }
            if (method.equals("tools/call")) {
                String name = root.path("params").path("name").asText(null);
                if (name == null || grant.tools().stream().noneMatch(tool -> tool.name().equals(name)))
                    return error(id, -32602, "MCP tool unavailable");
            }
            List<McpSchema.Tool> approved = views.tools(grant.id());
            if (approved.size() != grant.tools().size()) return error(id, -32603, "MCP tools unavailable");
            var request = json.treeToValue(root, McpSchema.JSONRPCRequest.class);
            var transport = new HandlerTransport();
            var builder = McpServer.sync(transport).jsonMapper(protocolJson)
                    .serverInfo("Forge MCP Gateway", "1")
                    .capabilities(new McpSchema.ServerCapabilities(null, null, null, null, null,
                            new McpSchema.ServerCapabilities.ToolCapabilities(false)))
                    .immediateExecution(true).requestTimeout(timeout);
            for (var tool : approved) {
                var approval = grant.tools().stream().filter(value -> value.name().equals(tool.name()))
                        .findFirst().orElseThrow();
                builder.toolCall(tool, (context, call) -> invoke(token, grant, approval, call));
            }
            var server = builder.build();
            try {
                var response = transport.handler.handleRequest(McpTransportContext.EMPTY, request).block(timeout);
                if (response == null) return error(id, -32603, "MCP request failed");
                if (response.error() != null)
                    return error(id, response.error().code() == null ? -32603 : response.error().code(),
                            "MCP request failed");
                return new ProtocolResponse(200, protocolJson.writeValueAsBytes(response));
            } finally {
                server.close();
            }
        } catch (Exception failure) {
            return error(id, -32603, "MCP request failed");
        }
    }

    private McpSchema.CallToolResult invoke(String token, McpRuntimeGrant grant, McpAllowedTool approval,
                                             McpSchema.CallToolRequest request) {
        try {
            String arguments = json.writeValueAsString(request.arguments());
            McpToolCallResult result = runtime.call(token, grant.connectionId(), approval.name(),
                    approval.schemaFingerprint(), arguments);
            List<McpSchema.Content> content = protocolJson.readValue(result.contentJson(), new TypeRef<>() {});
            Object structured = result.structuredContentJson() == null ? null
                    : protocolJson.readValue(result.structuredContentJson(), Object.class);
            return new McpSchema.CallToolResult(content, result.isError(), structured, null);
        } catch (IOException failure) {
            throw new IllegalStateException("MCP tool result unavailable");
        }
    }

    private ProtocolResponse error(Object id, int code, String message) {
        try {
            var response = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, id, null,
                    new McpSchema.JSONRPCResponse.JSONRPCError(code, message, null));
            return new ProtocolResponse(200, protocolJson.writeValueAsBytes(response));
        } catch (IOException impossible) {
            throw new IllegalStateException("MCP JSON mapper unavailable");
        }
    }

    public record ProtocolResponse(int status, byte[] body) {}

    private static final class HandlerTransport implements McpStatelessServerTransport {
        private McpStatelessServerHandler handler;
        @Override public void setMcpHandler(McpStatelessServerHandler handler) { this.handler = handler; }
        @Override public Mono<Void> closeGracefully() { return Mono.empty(); }
    }

}
