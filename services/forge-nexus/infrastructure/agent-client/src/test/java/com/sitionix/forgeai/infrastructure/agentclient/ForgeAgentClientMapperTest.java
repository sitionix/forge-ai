package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentModelSelection;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunFailure;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunOutputDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunStatus;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTask;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskPage;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskSummary;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRunConnection;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRunNode;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRunPort;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunGraph;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunStatus;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunSummary;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectTaskCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowRunCommand;
import com.sitionix.forgeai.domain.model.agentproxy.Node;
import com.sitionix.forgeai.domain.model.agentproxy.NodeInputMode;
import com.sitionix.forgeai.domain.model.agentproxy.NodePort;
import com.sitionix.forgeai.domain.model.agentproxy.NodePosition;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentDefinitionCommand;
import com.sitionix.forgeai.domain.model.agentproxy.SaveAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.WorkflowConnection;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionListResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentDefinitionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentModelSelectionDto;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentProjectResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.AgentWorkflowResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.CreateWorkflowRunRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.CreateProjectTaskRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeRunFailureResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeRunResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodePositionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodePositionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodePortRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodePortResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.NodeResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.SaveAgentWorkflowRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectTaskPageResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectTaskResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectTaskSummaryResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.RunConnectionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.RunNodeResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.RunPortResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowConnectionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowConnectionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowRunGraphResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowRunResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowRunSummaryResponse;
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
    private static final UUID INPUT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OUTPUT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID CONNECTION_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID NODE_RUN_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID TASK_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
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
    void projectTaskCommandsAndResponsesMapSuccessfully() throws Exception {
        assertThat(this.mapper.toRequest(new CreateAgentProjectTaskCommand("Check calculation", "Count letters.", WORKFLOW_ID)))
                .isEqualTo(new CreateProjectTaskRequest("Check calculation", "Count letters.", WORKFLOW_ID));

        final var summaryResponse = new ProjectTaskSummaryResponse(TASK_ID, PROJECT_ID, "Check calculation", WORKFLOW_ID, "Full Testing", RUN_ID, AgentWorkflowRunStatus.QUEUED, CREATED, UPDATED);
        assertThat(this.mapper.toDomain(summaryResponse)).isEqualTo(new AgentProjectTaskSummary(
                TASK_ID,
                PROJECT_ID,
                "Check calculation",
                WORKFLOW_ID,
                "Full Testing",
                RUN_ID,
                AgentWorkflowRunStatus.QUEUED,
                CREATED,
                UPDATED
        ));

        assertThat(this.mapper.toDomain(new ProjectTaskPageResponse(List.of(summaryResponse), 2, 10, 21, 3)))
                .isEqualTo(new AgentProjectTaskPage(
                        List.of(new AgentProjectTaskSummary(
                                TASK_ID,
                                PROJECT_ID,
                                "Check calculation",
                                WORKFLOW_ID,
                                "Full Testing",
                                RUN_ID,
                                AgentWorkflowRunStatus.QUEUED,
                                CREATED,
                                UPDATED
                        )),
                        2,
                        10,
                        21,
                        3
                ));

        final var run = new WorkflowRunSummaryResponse(RUN_ID, WORKFLOW_ID, TASK_ID, "Full Testing", AgentWorkflowRunStatus.QUEUED, CREATED, null, null);
        final var taskResponse = new ProjectTaskResponse(TASK_ID, PROJECT_ID, "Check calculation", "Count letters.", WORKFLOW_ID, List.of(run), this.objectMapper.readTree("{\"answer\":\"done\"}"), CREATED, UPDATED);
        assertThat(this.mapper.toDomain(taskResponse)).isEqualTo(new AgentProjectTask(
                TASK_ID,
                PROJECT_ID,
                "Check calculation",
                "Count letters.",
                WORKFLOW_ID,
                List.of(new AgentWorkflowRunSummary(RUN_ID, WORKFLOW_ID, TASK_ID, "Full Testing", AgentWorkflowRunStatus.QUEUED, CREATED, null, null)),
                new AgentNodeRunOutputDocument("{\"answer\":\"done\"}"),
                CREATED,
                UPDATED
        ));
    }

    @Test
    void agentCommandMapsToRequest() throws Exception {
        final var command = new SaveAgentDefinitionCommand(
                "Backend Implementer",
                "Do backend work.",
                new AgentOutputSchemaDocument("{\"type\":\"object\"}"),
                null
        );
        assertThat(this.mapper.toRequest(command)).isEqualTo(new AgentDefinitionRequest(
                "Backend Implementer",
                "Do backend work.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                null
        ));
    }

    @Test
    void validProjectAndAgentResponsesMapSuccessfully() throws Exception {
        assertThat(this.mapper.toDomain(new AgentProjectResponse(PROJECT_ID, "Sitionix", CREATED, UPDATED)))
                .isEqualTo(new AgentProject(PROJECT_ID, "Sitionix", CREATED, UPDATED));

        assertThat(this.mapper.toDomain(new AgentDefinitionListResponse(
                AGENT_ID,
                PROJECT_ID,
                "Backend Implementer",
                new AgentModelSelectionDto("codex", "discovered-model", "xhigh"),
                CREATED,
                UPDATED
        )))
                .isEqualTo(new AgentDefinitionListItem(
                        AGENT_ID,
                        PROJECT_ID,
                        "Backend Implementer",
                        new AgentModelSelection("codex", "discovered-model", "xhigh"),
                        CREATED,
                        UPDATED
                ));

        final var response = new AgentDefinitionResponse(
                AGENT_ID,
                PROJECT_ID,
                "Backend Implementer",
                "Do backend work.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                null,
                CREATED,
                UPDATED
        );
        assertThat(this.mapper.toDomain(response)).isEqualTo(new AgentDefinitionDetails(
                AGENT_ID,
                PROJECT_ID,
                "Backend Implementer",
                "Do backend work.",
                new AgentOutputSchemaDocument("{\"type\":\"object\"}"),
                null,
                CREATED,
                UPDATED
        ));
    }

    @Test
    void workflowCommandsAndResponsesMapSuccessfully() {
        final var input = new NodePort(INPUT_ID, "Review feedback", "Feedback produced by review.", 0);
        final var output = new NodePort(OUTPUT_ID, "Approved", "Continue when accepted.", 0);
        final var connection = new WorkflowConnection(CONNECTION_ID, OUTPUT_ID, INPUT_ID);
        final var node = new Node(
                NODE_ID,
                AGENT_ID,
                NodeInputMode.DEPENDENCIES_ONLY,
                List.of(input),
                List.of(output),
                new NodePosition(1.0, 2.0)
        );
        assertThat(this.mapper.toRequest(new CreateAgentWorkflowCommand("Full Testing")))
                .isEqualTo(new AgentWorkflowRequest("Full Testing"));
        assertThat(this.mapper.toRequest(new SaveAgentWorkflowCommand("Full Testing", List.of(node), List.of(connection), INPUT_ID, OUTPUT_ID)))
                .isEqualTo(new SaveAgentWorkflowRequest(
                        "Full Testing",
                        List.of(new NodeRequest(
                                NODE_ID,
                                AGENT_ID,
                                NodeInputMode.DEPENDENCIES_ONLY.name(),
                                List.of(new NodePortRequest(INPUT_ID, "Review feedback", "Feedback produced by review.", 0)),
                                List.of(new NodePortRequest(OUTPUT_ID, "Approved", "Continue when accepted.", 0)),
                                new NodePositionRequest(1.0, 2.0)
                        )),
                        List.of(new WorkflowConnectionRequest(CONNECTION_ID, OUTPUT_ID, INPUT_ID)),
                        INPUT_ID,
                        OUTPUT_ID
                ));

        assertThat(this.mapper.toDomain(new AgentWorkflowResponse(
                WORKFLOW_ID,
                PROJECT_ID,
                "Full Testing",
                List.of(new NodeResponse(
                        NODE_ID,
                        AGENT_ID,
                        NodeInputMode.DEPENDENCIES_ONLY.name(),
                        List.of(new NodePortResponse(INPUT_ID, "Review feedback", "Feedback produced by review.", 0)),
                        List.of(new NodePortResponse(OUTPUT_ID, "Approved", "Continue when accepted.", 0)),
                        new NodePositionResponse(1.0, 2.0)
                )),
                List.of(new WorkflowConnectionResponse(CONNECTION_ID, OUTPUT_ID, INPUT_ID)),
                INPUT_ID,
                OUTPUT_ID,
                CREATED,
                UPDATED
        ))).isEqualTo(new AgentWorkflow(WORKFLOW_ID, PROJECT_ID, "Full Testing", List.of(node), List.of(connection), INPUT_ID, OUTPUT_ID, CREATED, UPDATED));
    }

    @Test
    void workflowRunCommandsAndResponsesMapSuccessfully() throws Exception {
        assertThat(this.mapper.toRequest(new CreateAgentWorkflowRunCommand("Review auth changes.")))
                .isEqualTo(new CreateWorkflowRunRequest("Review auth changes."));

        assertThat(this.mapper.toDomain(new WorkflowRunSummaryResponse(
                RUN_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                AgentWorkflowRunStatus.QUEUED,
                CREATED,
                null,
                null
        ))).isEqualTo(new AgentWorkflowRunSummary(
                RUN_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                AgentWorkflowRunStatus.QUEUED,
                CREATED,
                null,
                null
        ));

        final var nodeRunResponse = new NodeRunResponse(
                NODE_RUN_ID,
                NODE_ID,
                AGENT_ID,
                "Analyzer",
                "Analyze changes.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                null,
                new NodePositionResponse(1.0, 2.0),
                UUID.fromString("99999999-0000-4000-8000-000000000001"),
                null,
                null,
                null,
                AgentNodeRunStatus.PENDING,
                this.objectMapper.readTree("{\"summary\":\"done\"}"),
                new NodeRunFailureResponse("ERR", "Failed"),
                CREATED,
                null,
                null
        );
        assertThat(this.mapper.toDomain(new WorkflowRunResponse(
                RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                "Review auth changes.",
                AgentWorkflowRunStatus.QUEUED,
                List.of(nodeRunResponse),
                List.of(),
                List.of(),
                null,
                this.objectMapper.readTree("{\"task\":\"result\"}"),
                NODE_RUN_ID,
                CREATED,
                null,
                null
        ))).isEqualTo(new AgentWorkflowRun(
                RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                "Review auth changes.",
                AgentWorkflowRunStatus.QUEUED,
                List.of(new AgentNodeRun(
                        NODE_RUN_ID,
                        NODE_ID,
                        AGENT_ID,
                        "Analyzer",
                        "Analyze changes.",
                        new AgentOutputSchemaDocument("{\"type\":\"object\"}"),
                        NodeInputMode.TASK_AND_DEPENDENCIES,
                        new NodePosition(1.0, 2.0),
                        UUID.fromString("99999999-0000-4000-8000-000000000001"),
                        null,
                        null,
                        null,
                        AgentNodeRunStatus.PENDING,
                        new AgentNodeRunOutputDocument("{\"summary\":\"done\"}"),
                        new AgentNodeRunFailure("ERR", "Failed"),
                        CREATED,
                        null,
                        null
                )),
                List.of(),
                List.of(),
                null,
                new AgentNodeRunOutputDocument("{\"task\":\"result\"}"),
                NODE_RUN_ID,
                CREATED,
                null,
                null
        ));
    }

    @Test
    void workflowRunRuntimeGraphMapsSuccessfully() throws Exception {
        final UUID inputPortId = UUID.fromString("88888888-0000-4000-8000-000000000001");
        final UUID outputPortId = UUID.fromString("88888888-0000-4000-8000-000000000002");
        final UUID connectionId = UUID.fromString("88888888-0000-4000-8000-000000000003");
        final UUID frameId = UUID.fromString("99999999-0000-4000-8000-000000000001");
        final Instant finishedAt = Instant.parse("2026-08-04T00:02:00Z");
        final var nodeRunResponse = new NodeRunResponse(
                NODE_RUN_ID,
                NODE_ID,
                AGENT_ID,
                "Analyzer",
                "Analyze changes.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                NodeInputMode.DEPENDENCIES_ONLY.name(),
                new NodePositionResponse(1.0, 2.0),
                frameId,
                inputPortId,
                null,
                outputPortId,
                AgentNodeRunStatus.SUCCEEDED,
                this.objectMapper.readTree("{\"summary\":\"done\"}"),
                null,
                CREATED,
                CREATED,
                finishedAt
        );
        final WorkflowRunGraphResponse graph = new WorkflowRunGraphResponse(
                inputPortId,
                outputPortId,
                List.of(new RunNodeResponse(
                        NODE_ID,
                        "Analyzer",
                        new NodePositionResponse(1.0, 2.0)
                )),
                List.of(
                        new RunPortResponse(inputPortId, NODE_ID, "INPUT", "Initial", 0),
                        new RunPortResponse(outputPortId, NODE_ID, "OUTPUT", "Done", 0)
                ),
                List.of(new RunConnectionResponse(connectionId, outputPortId, inputPortId))
        );

        assertThat(this.mapper.toDomain(new WorkflowRunResponse(
                RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                "Review auth changes.",
                AgentWorkflowRunStatus.RUNNING,
                List.of(nodeRunResponse),
                List.of(),
                List.of(),
                graph,
                null,
                null,
                CREATED,
                CREATED,
                null
        ))).isEqualTo(new AgentWorkflowRun(
                RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                "Review auth changes.",
                AgentWorkflowRunStatus.RUNNING,
                List.of(new AgentNodeRun(
                        NODE_RUN_ID,
                        NODE_ID,
                        AGENT_ID,
                        "Analyzer",
                        "Analyze changes.",
                        new AgentOutputSchemaDocument("{\"type\":\"object\"}"),
                        NodeInputMode.DEPENDENCIES_ONLY,
                        new NodePosition(1.0, 2.0),
                        frameId,
                        inputPortId,
                        null,
                        outputPortId,
                        AgentNodeRunStatus.SUCCEEDED,
                        new AgentNodeRunOutputDocument("{\"summary\":\"done\"}"),
                        null,
                        CREATED,
                        CREATED,
                        finishedAt
                )),
                List.of(),
                List.of(),
                new AgentWorkflowRunGraph(
                        inputPortId,
                        outputPortId,
                        List.of(new AgentRunNode(
                                NODE_ID,
                                "Analyzer",
                                new NodePosition(1.0, 2.0)
                        )),
                        List.of(
                                new AgentRunPort(inputPortId, NODE_ID, "INPUT", "Initial", 0),
                                new AgentRunPort(outputPortId, NODE_ID, "OUTPUT", "Done", 0)
                        ),
                        List.of(new AgentRunConnection(connectionId, outputPortId, inputPortId))
                ),
                null,
                null,
                CREATED,
                CREATED,
                null
        ));
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
                List.of(new NodeResponse(NODE_ID, null, new NodePositionResponse(1.0, 2.0))),
                List.of(),
                null,
                CREATED,
                UPDATED
        )))
                .isInstanceOf(HttpMessageConversionException.class)
                .hasMessageContaining("node.targetId");
        assertThatThrownBy(() -> this.mapper.requireList(null, "agents"))
                .isInstanceOf(HttpMessageConversionException.class)
                .hasMessageContaining("agents");
        assertThatThrownBy(() -> this.mapper.toDomain(new WorkflowRunResponse(
                RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                "Review auth changes.",
                AgentWorkflowRunStatus.QUEUED,
                List.of(new NodeRunResponse(NODE_RUN_ID, NODE_ID, AGENT_ID, "Analyzer", "Analyze", this.objectMapper.readTree("[]"), new NodePositionResponse(1.0, 2.0), AgentNodeRunStatus.PENDING, null, null, CREATED, null, null)),
                CREATED,
                null,
                null
        )))
                .isInstanceOf(HttpMessageConversionException.class)
                .hasMessageContaining("nodeRun.agentOutputSchema");
    }

    private AgentDefinitionResponse responseWithOutputSchema(final com.fasterxml.jackson.databind.JsonNode outputSchema) {
        return new AgentDefinitionResponse(
                AGENT_ID,
                PROJECT_ID,
                "Backend Implementer",
                "Do backend work.",
                outputSchema,
                null,
                CREATED,
                UPDATED
        );
    }
}
