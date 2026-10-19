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
