package com.sitionix.forgeai.api.agentproxy;

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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentProxyApiMapperTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID WORKFLOW_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID NODE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant CREATED = Instant.parse("2026-08-04T00:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-08-04T00:01:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentProxyApiMapper mapper = new AgentProxyApiMapper(this.objectMapper);

    @Test
    void mapsProjectRequestToCommand() {
        assertThat(this.mapper.toCommand(new AgentProjectRequest("Sitionix")))
                .isEqualTo(new CreateAgentProjectCommand("Sitionix"));
    }

    @Test
    void mapsAgentRequestToCommandWithoutDependencies() throws Exception {
        final var request = new AgentDefinitionRequest("Backend", "Do work.", this.objectMapper.readTree("{\"type\":\"object\"}"));

        assertThat(this.mapper.toCommand(request)).isEqualTo(new SaveAgentDefinitionCommand(
                "Backend",
                "Do work.",
                new AgentOutputSchemaDocument("{\"type\":\"object\"}")
        ));
    }

    @Test
    void rejectsNonObjectOutputSchemaAsLocalInvalidRequest() throws Exception {
        final var request = new AgentDefinitionRequest("Backend", "Do work.", this.objectMapper.readTree("[]"));

        assertThatThrownBy(() -> this.mapper.toCommand(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");
    }

    @Test
    void mapsProjectToTypedResponse() {
        assertThat(this.mapper.toResponse(new AgentProject(PROJECT_ID, "Sitionix", CREATED, UPDATED)))
                .isEqualTo(new AgentProjectResponse(PROJECT_ID, "Sitionix", CREATED, UPDATED));
    }

    @Test
    void mapsAgentListItemToTypedResponseWithoutDependencies() {
        assertThat(this.mapper.toResponse(new AgentDefinitionListItem(AGENT_ID, PROJECT_ID, "Backend", CREATED, UPDATED)))
                .isEqualTo(new AgentDefinitionListResponse(AGENT_ID, PROJECT_ID, "Backend", CREATED, UPDATED));
    }

    @Test
    void mapsAgentDetailsToTypedResponseWithoutDependencies() throws Exception {
        final var agent = new AgentDefinitionDetails(
                AGENT_ID,
                PROJECT_ID,
                "Backend",
                "Do work.",
                new AgentOutputSchemaDocument("{\"type\":\"object\"}"),
                CREATED,
                UPDATED
        );

        assertThat(this.mapper.toResponse(agent)).isEqualTo(new AgentDefinitionResponse(
                AGENT_ID,
                PROJECT_ID,
                "Backend",
                "Do work.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                CREATED,
                UPDATED
        ));
    }

    @Test
    void mapsWorkflowRequestsAndResponses() {
        assertThat(this.mapper.toCommand(new AgentWorkflowRequest("Full Testing")))
                .isEqualTo(new CreateAgentWorkflowCommand("Full Testing"));

        final var nodeRequest = new NodeRequest(NODE_ID, AGENT_ID, List.of(), new NodePositionRequest(1.0, 2.0));
        assertThat(this.mapper.toCommand(new SaveAgentWorkflowRequest("Full Testing", List.of(nodeRequest))))
                .isEqualTo(new SaveAgentWorkflowCommand(
                        "Full Testing",
                        List.of(new Node(NODE_ID, AGENT_ID, List.of(), new NodePosition(1.0, 2.0)))
                ));

        final var workflow = new AgentWorkflow(
                WORKFLOW_ID,
                PROJECT_ID,
                "Full Testing",
                List.of(new Node(NODE_ID, AGENT_ID, List.of(), new NodePosition(1.0, 2.0))),
                CREATED,
                UPDATED
        );
        assertThat(this.mapper.toResponse(workflow)).isEqualTo(new AgentWorkflowResponse(
                WORKFLOW_ID,
                PROJECT_ID,
                "Full Testing",
                List.of(new NodeResponse(NODE_ID, AGENT_ID, List.of(), new NodePositionResponse(1.0, 2.0))),
                CREATED,
                UPDATED
        ));
    }
}
