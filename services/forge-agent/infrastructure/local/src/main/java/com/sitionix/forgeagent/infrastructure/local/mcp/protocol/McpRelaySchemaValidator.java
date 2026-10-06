package com.sitionix.forgeagent.infrastructure.local.mcp.protocol;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.io.IOException;
import java.util.Map;

/**
 * Gateway schemas belong to the upstream server, which validates its own inputs and outputs.
 * Keep them opaque here: no local dialect assumptions or external reference resolution.
 */
public final class McpRelaySchemaValidator implements JsonSchemaValidator {
    private final McpJsonMapper json;

    public McpRelaySchemaValidator(McpJsonMapper json) { this.json = json; }

    @Override public ValidationResponse validateSchema(Map<String, Object> schema) {
        return ValidationResponse.asValid(null);
    }

    @Override public ValidationResponse validate(Map<String, Object> schema, Object structuredContent) {
        try { return ValidationResponse.asValid(json.writeValueAsString(structuredContent)); }
        catch (IOException failure) { return ValidationResponse.asInvalid("Invalid MCP structured result"); }
    }
}
