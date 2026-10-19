package com.sitionix.forgeagent.api.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.McpToolSummary;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class McpToolSchemaResponseTest {
    @Test void transportsNestedInputAndOptionalOutputSchemas() throws Exception {
        var json = new ObjectMapper();
        var tool = json.readValue("""
                {"name":"lookup","description":"Lookup","schemaFingerprint":"sha",
                 "inputSchema":{"type":"object","properties":{"limit":{"type":"integer","default":0}}},
                 "outputSchema":{"type":"object","properties":{"found":{"type":"boolean"}}}}
                """, McpToolSummary.class);
        var response = json.valueToTree(McpProbeResponse.tools(List.of(tool))).get(0);
        assertThat(response.at("/inputSchema/properties/limit/default").intValue()).isZero();
        assertThat(response.at("/outputSchema/properties/found/type").asText()).isEqualTo("boolean");
        var legacy = json.valueToTree(McpProbeResponse.tools(List.of(new McpToolSummary("old", null, "old-sha")))).get(0);
        assertThat(legacy.path("inputSchema").isNull()).isTrue();
        assertThat(legacy.path("outputSchema").isNull()).isTrue();
    }
}
