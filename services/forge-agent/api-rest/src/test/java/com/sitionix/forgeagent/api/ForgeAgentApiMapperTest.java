package com.sitionix.forgeagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.api.dto.AgentListResponse;
import com.sitionix.forgeagent.api.dto.AgentResponse;
import com.sitionix.forgeagent.api.dto.CreateProjectRequest;
import com.sitionix.forgeagent.api.dto.CreateWorkflowRequest;
import com.sitionix.forgeagent.api.dto.NodePositionRequest;
import com.sitionix.forgeagent.api.dto.NodePositionResponse;
import com.sitionix.forgeagent.api.dto.NodeRequest;
import com.sitionix.forgeagent.api.dto.NodeResponse;
import com.sitionix.forgeagent.api.dto.ProjectResponse;
import com.sitionix.forgeagent.api.dto.SaveAgentRequest;
import com.sitionix.forgeagent.api.dto.SaveWorkflowRequest;
import com.sitionix.forgeagent.api.dto.WorkflowResponse;
import com.sitionix.forgeagent.application.usecase.CreateProjectCommand;
import com.sitionix.forgeagent.application.usecase.CreateWorkflowCommand;
import com.sitionix.forgeagent.application.usecase.SaveAgentCommand;
import com.sitionix.forgeagent.application.usecase.SaveWorkflowCommand;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDetails;
import com.sitionix.forgeagent.domain.model.AgentListItem;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.model.Workflow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ForgeAgentApiMapperTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID WORKFLOW_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID NODE_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID NODE_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final Instant CREATED = Instant.parse("2026-08-04T00:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-08-04T00:01:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ForgeAgentApiMapper mapper = new ForgeAgentApiMapper(this.objectMapper);

    @Test
    void mapsProjectRequestToCommand() {
        assertThat(this.mapper.toCommand(new CreateProjectRequest("Sitionix")))
                .isEqualTo(new CreateProjectCommand("Sitionix"));
    }

    @Test
    void mapsAgentRequestToCommand() throws Exception {
        final SaveAgentRequest request = new SaveAgentRequest(
                "Analyzer",
                "Analyze changes.",
                this.objectMapper.readTree("{\"type\":\"object\"}")
        );

        assertThat(this.mapper.toCommand(request)).isEqualTo(new SaveAgentCommand(
                "Analyzer",
                "Analyze changes.",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}")
        ));
    }

    @Test
    void rejectsOutputSchemaWithNonObjectRoot() throws Exception {
        final SaveAgentRequest request = new SaveAgentRequest("Analyzer", "Analyze changes.", this.objectMapper.readTree("[]"));

        assertThatThrownBy(() -> this.mapper.toCommand(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Output schema must be a JSON object.");
    }

    @Test
    void mapsProjectToResponse() {
        assertThat(this.mapper.toResponse(new Project(PROJECT_ID, "Sitionix", "sitionix", CREATED, UPDATED)))
                .isEqualTo(new ProjectResponse(PROJECT_ID, "Sitionix", CREATED, UPDATED));
    }

    @Test
    void mapsAgentListItemToResponse() {
        final var item = new AgentListItem(AGENT_ID, PROJECT_ID, "Analyzer", CREATED, UPDATED);

        assertThat(this.mapper.toResponse(item))
                .isEqualTo(new AgentListResponse(AGENT_ID, PROJECT_ID, "Analyzer", CREATED, UPDATED));
    }

    @Test
    void mapsAgentDetailsToResponse() throws Exception {
        final var agent = new AgentDetails(
                AGENT_ID,
                PROJECT_ID,
                "Analyzer",
                "Analyze changes.",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                CREATED,
                UPDATED
        );

        assertThat(this.mapper.toResponse(agent)).isEqualTo(new AgentResponse(
                AGENT_ID,
                PROJECT_ID,
                "Analyzer",
                "Analyze changes.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                CREATED,
                UPDATED
        ));
    }

    @Test
    void mapsWorkflowRequestsAndResponses() {
        assertThat(this.mapper.toCommand(new CreateWorkflowRequest("Full Testing")))
                .isEqualTo(new CreateWorkflowCommand("Full Testing"));

        final SaveWorkflowRequest saveRequest = new SaveWorkflowRequest(
                "Full Testing",
                List.of(new NodeRequest(NODE_A, AGENT_ID, List.of(), new NodePositionRequest(1.0, 2.0)),
                        new NodeRequest(NODE_B, AGENT_ID, List.of(NODE_A), new NodePositionRequest(3.0, 4.0)))
        );
        assertThat(this.mapper.toCommand(saveRequest)).isEqualTo(new SaveWorkflowCommand(
                "Full Testing",
                List.of(new Node(NODE_A, AGENT_ID, List.of(), new NodePosition(1.0, 2.0)),
                        new Node(NODE_B, AGENT_ID, List.of(NODE_A), new NodePosition(3.0, 4.0)))
        ));

        final Workflow workflow = new Workflow(
                WORKFLOW_ID,
                PROJECT_ID,
                "Full Testing",
                "full testing",
                List.of(new Node(NODE_B, AGENT_ID, List.of(NODE_A), new NodePosition(3.0, 4.0))),
                CREATED,
                UPDATED
        );
        assertThat(this.mapper.toResponse(workflow)).isEqualTo(new WorkflowResponse(
                WORKFLOW_ID,
                PROJECT_ID,
                "Full Testing",
                List.of(new NodeResponse(NODE_B, AGENT_ID, List.of(NODE_A), new NodePositionResponse(3.0, 4.0))),
                CREATED,
                UPDATED
        ));
    }
}
