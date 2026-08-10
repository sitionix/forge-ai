package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.Node;
import com.sitionix.forgeai.domain.model.agentproxy.NodePosition;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentWorkflowCommand;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionListResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodePositionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodePositionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.SaveAgentWorkflowRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConversionException;

class ForgeAgentClientMapperTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID WORKFLOW_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID NODE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant CREATED = Instant.parse("2026-08-04T00:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-08-04T00:01:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ForgeAgentClientMapper mapper = new ForgeAgentClientMapper(this.objectMapper);

    @Test
    void projectCommandMapsToRequest() {
        assertThat(this.mapper.toRequest(new CreateAgentProjectCommand("Sitionix")))
                .isEqualTo(new AgentProjectRequest("Sitionix"));
    }

    @Test
    void agentCommandMapsToRequest() throws Exception {
        final var command = new SaveAgentDefinitionCommand(
                "Backend Implementer",
                "Do backend work.",
                new AgentOutputSchemaDocument("{\"type\":\"object\"}")
        );
        assertThat(this.mapper.toRequest(command)).isEqualTo(new AgentDefinitionRequest(
                "Backend Implementer",
                "Do backend work.",
                this.objectMapper.readTree("{\"type\":\"object\"}")
        ));
    }

    @Test
    void validProjectAndAgentResponsesMapSuccessfully() throws Exception {
        assertThat(this.mapper.toDomain(new AgentProjectResponse(PROJECT_ID, "Sitionix", CREATED, UPDATED)))
                .isEqualTo(new AgentProject(PROJECT_ID, "Sitionix", CREATED, UPDATED));

        assertThat(this.mapper.toDomain(new AgentDefinitionListResponse(AGENT_ID, PROJECT_ID, "Backend Implementer", CREATED, UPDATED)))
                .isEqualTo(new AgentDefinitionListItem(AGENT_ID, PROJECT_ID, "Backend Implementer", CREATED, UPDATED));

        final var response = new AgentDefinitionResponse(
                AGENT_ID,
                PROJECT_ID,
                "Backend Implementer",
                "Do backend work.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                CREATED,
                UPDATED
        );
        assertThat(this.mapper.toDomain(response)).isEqualTo(new AgentDefinitionDetails(
                AGENT_ID,
                PROJECT_ID,
                "Backend Implementer",
                "Do backend work.",
                new AgentOutputSchemaDocument("{\"type\":\"object\"}"),
                CREATED,
                UPDATED
        ));
    }

    @Test
    void workflowCommandsAndResponsesMapSuccessfully() {
        final var node = new Node(NODE_ID, AGENT_ID, List.of(), new NodePosition(1.0, 2.0));
        assertThat(this.mapper.toRequest(new CreateAgentWorkflowCommand("Full Testing")))
                .isEqualTo(new AgentWorkflowRequest("Full Testing"));
        assertThat(this.mapper.toRequest(new SaveAgentWorkflowCommand("Full Testing", List.of(node))))
                .isEqualTo(new SaveAgentWorkflowRequest(
                        "Full Testing",
                        List.of(new NodeRequest(NODE_ID, AGENT_ID, List.of(), new NodePositionRequest(1.0, 2.0)))
                ));

        assertThat(this.mapper.toDomain(new AgentWorkflowResponse(
                WORKFLOW_ID,
                PROJECT_ID,
                "Full Testing",
                List.of(new NodeResponse(NODE_ID, AGENT_ID, List.of(), new NodePositionResponse(1.0, 2.0))),
                CREATED,
                UPDATED
        ))).isEqualTo(new AgentWorkflow(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(node), CREATED, UPDATED));
    }

    @Test
    void malformedUpstreamResponsesFailClosed() throws Exception {
        assertThatThrownBy(() -> this.mapper.toDomain(this.responseWithOutputSchema(null)))
                .isInstanceOf(HttpMessageConversionException.class)
                .hasMessageContaining("outputSchema");
        assertThatThrownBy(() -> this.mapper.toDomain(this.responseWithOutputSchema(this.objectMapper.readTree("[]"))))
                .isInstanceOf(HttpMessageConversionException.class)
                .hasMessageContaining("outputSchema");
        assertThatThrownBy(() -> this.mapper.toDomain((AgentDefinitionResponse) null))
                .isInstanceOf(HttpMessageConversionException.class)
                .hasMessageContaining("must not be null");
        assertThatThrownBy(() -> this.mapper.toDomain(new AgentWorkflowResponse(
                WORKFLOW_ID,
                PROJECT_ID,
                "Full Testing",
                List.of(new NodeResponse(NODE_ID, null, List.of(), new NodePositionResponse(1.0, 2.0))),
                CREATED,
                UPDATED
        )))
                .isInstanceOf(HttpMessageConversionException.class)
                .hasMessageContaining("node.targetId");
        assertThatThrownBy(() -> this.mapper.requireList(null, "agents"))
                .isInstanceOf(HttpMessageConversionException.class)
                .hasMessageContaining("agents");
    }

    private AgentDefinitionResponse responseWithOutputSchema(final com.fasterxml.jackson.databind.JsonNode outputSchema) {
        return new AgentDefinitionResponse(
                AGENT_ID,
                PROJECT_ID,
                "Backend Implementer",
                "Do backend work.",
                outputSchema,
                CREATED,
                UPDATED
        );
    }
}
