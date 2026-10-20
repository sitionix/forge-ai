package com.sitionix.forgeagent.it.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.mcp.McpConnectionService;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.McpToolInventoryRepository;
import com.sitionix.forgeagent.it.infra.*;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.util.*;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;
import static com.sitionix.forgeagent.it.infra.ForgeAgentMockMvcEndpoint.*;

@IntegrationTest
class ForgeAgentTriggerConfigurationIT extends AgentManagementFixture {
    @Autowired ForgeAgentTestManager forgeIt;
    @Autowired McpConnectionService connections;
    @Autowired McpToolInventoryRepository inventory;
    @Autowired ObjectMapper json;

    @Autowired com.sitionix.forgeagent.application.trigger.TriggerConfigurationValidator validator;

    @Test void preservesHighPrecisionNestedSchemaConstraintsThroughPostgresAndApiValidation() throws Exception {
        var connection=connections.create("Precision fixture",URI.create("https://example.test/precision"),McpAuthType.NONE,
                McpProjectAccess.all(),null,null,null);
        var exact=json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        String minimum="0.12345678901234567890123456789";
        Map<String,Object> schema=exact.readValue("""
                {"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer"},"enabled":{"type":"boolean"},
                "nested":{"type":"array","items":{"type":"object","properties":{"value":{"type":"number","minimum":0.12345678901234567890123456789,"multipleOf":0.00000000000000000000000000001}}}}},"required":["query"]}
                """,Map.class);
        var original=new McpToolSummary("read","Precision fixture","sha256:"+"a".repeat(64),schema,schema);
        inventory.replace(connection.installationId(),connection.id(),connection.endpoint(),McpAuthType.NONE,null,List.of(original),null);
        var saved=inventory.list(connection.installationId(),connection.id()).getFirst();
        assertThat(saved).isEqualTo(original);
        var api=exact.readTree(exact.writeValueAsString(com.sitionix.forgeagent.api.mcp.McpProbeResponse.tools(List.of(saved))));
        assertThat(api.at("/0/inputSchema/properties/nested/items/properties/value/minimum").decimalValue()).isEqualByComparingTo(minimum);
        assertThat(api.at("/0/outputSchema/properties/nested/items/properties/value/multipleOf").decimalValue()).isEqualByComparingTo("0.00000000000000000000000000001");
        inventory.approve(connection.installationId(),connection.id(),Set.of(new McpAllowedTool("read",saved.schemaFingerprint())));
        var definition=(com.fasterxml.jackson.databind.node.ObjectNode)exact.readTree("""
                {"version":1,"steps":[{"id":"11111111-1111-4111-8111-111111111111","toolName":"read","schemaFingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","arguments":{"query":"","nested":[{"value":0.12345678901234567890123456789}]},"bindings":[]}],"result":{"stepId":"11111111-1111-4111-8111-111111111111","pointer":""}}
                """);
        validator.validate(definition,List.of(saved),Set.of(new McpAllowedTool("read",saved.schemaFingerprint())));
        ((com.fasterxml.jackson.databind.node.ObjectNode)definition.at("/steps/0/arguments/nested/0")).put("value",new java.math.BigDecimal("0.12345678901234567890123456788"));
        assertThatThrownBy(()->validator.validate(definition,List.of(saved),Set.of(new McpAllowedTool("read",saved.schemaFingerprint()))))
                .isInstanceOf(com.sitionix.forgeagent.domain.exception.ValidationException.class);
        // A pre-schema inventory entry remains readable and approvals retain their original fingerprint semantics.
        var legacy=new McpToolSummary("legacy",null,"sha256:"+"b".repeat(64));
        inventory.replace(connection.installationId(),connection.id(),connection.endpoint(),McpAuthType.NONE,null,List.of(saved,legacy),null);
        assertThat(inventory.list(connection.installationId(),connection.id())).containsExactly(legacy,saved);
        assertThat(connections.get(connection.id()).allowedTools()).contains(new McpAllowedTool("read",saved.schemaFingerprint()));
    }

    @Test void persistsSchemasAndAuthoredConfigurationAndRejectsInvalidOrForeignUpdates() throws Exception {
        var connection=connections.create("Synthetic configuration",URI.create("https://example.test/mcp"),McpAuthType.NONE,
                McpProjectAccess.all(),null,null,null);
        Map<String,Object> schema=json.readValue("""
                {"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer"},"enabled":{"type":"boolean"},"optional":{"type":["string","null"]}},"required":["query"],"additionalProperties":false}
                """,Map.class);
        var tool=new McpToolSummary("read","Synthetic read","sha256:"+"a".repeat(64),schema,Map.of("type","object"));
        inventory.replace(connection.installationId(),connection.id(),connection.endpoint(),McpAuthType.NONE,null,List.of(tool),null);
        assertThat(inventory.list(connection.installationId(),connection.id()).getFirst()).isEqualTo(tool);
        inventory.approve(connection.installationId(),connection.id(),Set.of(new McpAllowedTool("read","sha256:"+"a".repeat(64))));
        AtomicReference<UUID> id=new AtomicReference<>();
        var connectionPath=PathParams.create().add("connectionId",connection.id());
        forgeIt.mockMvc().ping(CREATE_TRIGGER_CONFIGURATION).withPathParameters(connectionPath)
                .withRequest("authored-trigger-request.json").expectStatus(HttpStatus.CREATED)
                .expectResponse("authored-trigger-detail.json","id","connectionId")
                .andExpectPath(result->id.set(UUID.fromString(json.readTree(result.getResponse().getContentAsString()).path("id").asText())))
                .assertAndCreate();
        var path=PathParams.create().add("connectionId",connection.id()).add("id",id.get());
        forgeIt.mockMvc().ping(GET_TRIGGER_CONFIGURATION).withPathParameters(path).expectStatus(HttpStatus.OK)
                .expectResponse("authored-trigger-detail.json","id","connectionId").assertAndCreate();
        forgeIt.mockMvc().ping(UPDATE_TRIGGER_CONFIGURATION).withPathParameters(path).withRequest("authored-trigger-request.json")
                .expectStatus(HttpStatus.OK).expectResponse("authored-trigger-detail.json","id","connectionId").assertAndCreate();
        forgeIt.mockMvc().ping(INVALID_TRIGGER_CONFIGURATION).withPathParameters(path).withRequest("authored-trigger-invalid-request.json")
                .expectStatus(HttpStatus.BAD_REQUEST).assertAndCreate();
        forgeIt.mockMvc().ping(GET_TRIGGER_CONFIGURATION).withPathParameters(path).expectStatus(HttpStatus.OK)
                .expectResponse("authored-trigger-detail.json","id","connectionId").assertAndCreate();
        var other=connections.create("Other synthetic",URI.create("https://example.test/other"),McpAuthType.NONE,McpProjectAccess.all(),null,null,null);
        forgeIt.mockMvc().ping(MISSING_TRIGGER_CONFIGURATION).withPathParameters(PathParams.create().add("connectionId",other.id()).add("id",id.get()))
                .expectStatus(HttpStatus.NOT_FOUND).assertAndCreate();
        inventory.approve(connection.installationId(),connection.id(),Set.of());
        forgeIt.mockMvc().ping(INVALID_TRIGGER_CONFIGURATION).withPathParameters(path).withRequest("authored-trigger-request.json")
                .expectStatus(HttpStatus.BAD_REQUEST).assertAndCreate();
    }
}
