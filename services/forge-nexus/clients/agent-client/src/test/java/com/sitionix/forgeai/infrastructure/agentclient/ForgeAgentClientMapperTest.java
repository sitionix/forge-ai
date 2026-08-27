package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentConnectionResolution;
import com.sitionix.forgeai.domain.model.agentproxy.AgentModelSelection;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunFailure;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunOutputDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunStatus;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepository;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepositoryGitState;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTask;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskPage;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskSummary;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeTargetCandidate;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeTargetDiscoveryCommand;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRunConnection;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRunNode;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRunPort;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunGraph;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunStatus;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunSummary;
import com.sitionix.forgeai.domain.model.agentproxy.ConnectionResolutionType;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectTaskCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowRunCommand;
import com.sitionix.forgeai.domain.model.agentproxy.Node;
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
import com.sitionix.forgeai.infrastructure.agentclient.dto.ConnectionResolutionResponse;
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
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectRepositoryGitStateResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectRepositoryResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectTaskResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.ProjectTaskSummaryResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.RunConnectionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.RunNodeResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.RunPortResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.RuntimeTargetCandidateResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.RuntimeTargetDiscoveryRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowConnectionRequest;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowConnectionResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowRunGraphResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowRunResponse;
import com.sitionix.forgeai.infrastructure.agentclient.dto.WorkflowRunSummaryResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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
    private static final UUID REPOSITORY_ID = UUID.fromString("88888888-8888-4888-8888-888888888888");
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
        final var repositoryIds = List.of(UUID.fromString("88888888-8888-4888-8888-888888888888"));
        assertThat(this.mapper.toRequest(new CreateAgentProjectTaskCommand("Check calculation", "Count letters.", WORKFLOW_ID, repositoryIds)))
                .isEqualTo(new CreateProjectTaskRequest("Check calculation", "Count letters.", WORKFLOW_ID, repositoryIds));

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
        final var taskResponse = new ProjectTaskResponse(TASK_ID, PROJECT_ID, "Check calculation", "Count letters.", WORKFLOW_ID, repositoryIds, List.of(run), this.objectMapper.readTree("{\"answer\":\"done\"}"), CREATED, UPDATED);
        assertThat(this.mapper.toDomain(taskResponse)).isEqualTo(new AgentProjectTask(
                TASK_ID,
                PROJECT_ID,
                "Check calculation",
                "Count letters.",
                WORKFLOW_ID,
                repositoryIds,
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
    void projectRepositoryResponseMapsGitState() {
        final UUID repositoryId = UUID.fromString("88888888-8888-4888-8888-888888888888");
        final var response = new ProjectRepositoryResponse(
                repositoryId,
                PROJECT_ID,
                "service-a",
                "git@gitlab.com:company/service-a.git",
                true,
                new ProjectRepositoryGitStateResponse("main", "DIRTY", false),
                CREATED
        );

        assertThat(this.mapper.toDomain(response)).isEqualTo(new AgentProjectRepository(
                repositoryId,
                PROJECT_ID,
                "service-a",
                "git@gitlab.com:company/service-a.git",
                true,
                new AgentProjectRepositoryGitState("main", "DIRTY", false),
                CREATED
        ));
    }

    @Test
    void projectRepositoryResponseMapsInvalidClonedCheckoutGitState() {
        final UUID repositoryId = UUID.fromString("88888888-8888-4888-8888-888888888888");
        final var response = new ProjectRepositoryResponse(
                repositoryId,
                PROJECT_ID,
                "service-a",
                "git@gitlab.com:company/service-a.git",
                true,
                null,
                CREATED
        );

        assertThat(this.mapper.toDomain(response)).isEqualTo(new AgentProjectRepository(
                repositoryId,
                PROJECT_ID,
                "service-a",
                "git@gitlab.com:company/service-a.git",
                true,
                null,
                CREATED
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
                "opaque-input-mode",
                List.of(input),
                List.of(output),
                new NodePosition(1.0, 2.0),
                "GLOBAL"
        );
        assertThat(this.mapper.toRequest(new CreateAgentWorkflowCommand("Full Testing")))
                .isEqualTo(new AgentWorkflowRequest("Full Testing"));
        final var nodeWithoutAgentDefaults = new Node(NODE_ID, AGENT_ID, null, List.of(), List.of(), null, null);
        assertThat(this.mapper.toRequest(new SaveAgentWorkflowCommand(
                "Opaque workflow", List.of(nodeWithoutAgentDefaults), List.of(), null, null
        )).nodes().getFirst()).isEqualTo(new NodeRequest(
                NODE_ID, AGENT_ID, null, List.of(), List.of(), null, null
        ));
        final var nullCollections = this.mapper.toRequest(new SaveAgentWorkflowCommand(
                "Opaque workflow", null, null, null, null
        ));
        assertThat(nullCollections.nodes()).isNull();
        assertThat(nullCollections.connections()).isNull();
        assertThat(this.mapper.toRequest(new SaveAgentWorkflowCommand("Full Testing", List.of(node), List.of(connection), INPUT_ID, OUTPUT_ID)))
                .isEqualTo(new SaveAgentWorkflowRequest(
                        "Full Testing",
                        List.of(new NodeRequest(
                                NODE_ID,
                                AGENT_ID,
                                "opaque-input-mode",
                                List.of(new NodePortRequest(INPUT_ID, "Review feedback", "Feedback produced by review.", 0)),
                                List.of(new NodePortRequest(OUTPUT_ID, "Approved", "Continue when accepted.", 0)),
                                new NodePositionRequest(1.0, 2.0),
                                "GLOBAL"
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
                        "opaque-input-mode",
                        List.of(new NodePortResponse(INPUT_ID, "Review feedback", "Feedback produced by review.", 0)),
                        List.of(new NodePortResponse(OUTPUT_ID, "Approved", "Continue when accepted.", 0)),
                        new NodePositionResponse(1.0, 2.0),
                        "GLOBAL"
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

        final UUID executionFrameId = UUID.fromString("99999999-0000-4000-8000-000000000001");
        final UUID activationFrameId = UUID.fromString("99999999-0000-4000-8000-000000000002");
        final var nodeRunResponse = new NodeRunResponse(
                NODE_RUN_ID,
                NODE_ID,
                AGENT_ID,
                "Analyzer",
                "Analyze changes.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                "opaque-input-mode",
                new NodePositionResponse(1.0, 2.0),
                executionFrameId,
                INPUT_ID,
                activationFrameId,
                OUTPUT_ID,
                AgentNodeRunStatus.PENDING,
                this.objectMapper.readTree("{\"summary\":\"done\"}"),
                new NodeRunFailureResponse("ERR", "Failed"),
                CREATED,
                null,
                null,
                REPOSITORY_ID
        );
        final UUID resolutionId = UUID.fromString("77777777-0000-4000-8000-000000000001");
        final UUID frameId = UUID.fromString("99999999-0000-4000-8000-000000000001");
        final var connectionResolution = new ConnectionResolutionResponse(
                resolutionId,
                frameId,
                NODE_RUN_ID,
                CONNECTION_ID,
                INPUT_ID,
                ConnectionResolutionType.DELIVERED,
                this.objectMapper.readTree("{\"summary\":\"done\"}"),
                null,
                CREATED,
                REPOSITORY_ID
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
                List.of(connectionResolution),
                List.of(),
                null,
                this.objectMapper.readTree("{\"task\":\"result\"}"),
                NODE_RUN_ID,
                CREATED,
                null,
                null,
                java.util.List.of(REPOSITORY_ID)
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
                        "opaque-input-mode",
                        new NodePosition(1.0, 2.0),
                        executionFrameId,
                        INPUT_ID,
                        activationFrameId,
                        OUTPUT_ID,
                        AgentNodeRunStatus.PENDING,
                        new AgentNodeRunOutputDocument("{\"summary\":\"done\"}"),
                        new AgentNodeRunFailure("ERR", "Failed"),
                        CREATED,
                        null,
                        null,
                        REPOSITORY_ID
                )),
                List.of(new AgentConnectionResolution(
                        resolutionId,
                        frameId,
                        NODE_RUN_ID,
                        CONNECTION_ID,
                        INPUT_ID,
                        ConnectionResolutionType.DELIVERED,
                        new AgentNodeRunOutputDocument("{\"summary\":\"done\"}"),
                        null,
                        CREATED,
                        REPOSITORY_ID
                )),
                List.of(),
                null,
                new AgentNodeRunOutputDocument("{\"task\":\"result\"}"),
                NODE_RUN_ID,
                CREATED,
                null,
                null,
                java.util.List.of(REPOSITORY_ID)
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
                "opaque-input-mode",
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
                finishedAt,
                null
        );
        final WorkflowRunGraphResponse graph = new WorkflowRunGraphResponse(
                inputPortId,
                outputPortId,
                List.of(new RunNodeResponse(
                        NODE_ID,
                        "Analyzer",
                        new NodePositionResponse(1.0, 2.0),
                        "GLOBAL"
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
                null,
                java.util.List.of()
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
                        "opaque-input-mode",
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
                        finishedAt,
                        null
                )),
                List.of(),
                List.of(),
                new AgentWorkflowRunGraph(
                        inputPortId,
                        outputPortId,
                        List.of(new AgentRunNode(
                                NODE_ID,
                                "Analyzer",
                                new NodePosition(1.0, 2.0),
                                "GLOBAL"
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
                null,
                java.util.List.of()
        ));
    }

    @Test
    void structurallyMapsOpaqueAndNullUpstreamValues() throws Exception {
        assertThat(this.mapper.toDomain(this.responseWithOutputSchema(null)).outputSchema()).isNull();
        assertThat(this.mapper.toDomain(this.responseWithOutputSchema(this.objectMapper.readTree("[]")))
                .outputSchema().jsonObject()).isEqualTo("[]");

        final var workflow = this.mapper.toDomain(new AgentWorkflowResponse(
                WORKFLOW_ID, PROJECT_ID, "", null, null, null, CREATED, UPDATED));
        assertThat(workflow.name()).isEmpty();
        assertThat(workflow.nodes()).isNull();
        assertThat(workflow.connections()).isNull();

        final var run = this.mapper.toDomain(new WorkflowRunResponse(
                RUN_ID, null, WORKFLOW_ID, null, "", null, null,
                null, null, null, null, null, null, CREATED, null, null, null));
        assertThat(run.projectId()).isNull();
        assertThat(run.input()).isNull();
        assertThat(run.status()).isNull();
        assertThat(run.nodeRuns()).isNull();
        assertThat(run.repositoryIds()).isNull();
    }

    @Test
    void mapsCompleteLogDiscoveryCandidateMetadata() {
        final var response = new com.sitionix.forgeai.infrastructure.agentclient.dto.AgentLogTargetCandidateResponse(
                "web", "Web", com.sitionix.forgeai.domain.model.agentproxy.AgentLogTargetStatus.RUNNING,
                "web:latest", "demo", "web", "/repo/compose.yaml", true);

        assertThat(this.mapper.toDomain(response)).isEqualTo(
                new com.sitionix.forgeai.domain.model.agentproxy.AgentLogTargetCandidate(
                        "web", "Web", com.sitionix.forgeai.domain.model.agentproxy.AgentLogTargetStatus.RUNNING,
                        "web:latest", "demo", "web", "/repo/compose.yaml", true));
    }

    @Test
    void mapsRuntimeTargetDiscoveryDtos() {
        UUID sshId = UUID.randomUUID();
        var command = new AgentRuntimeTargetDiscoveryCommand("SSH", sshId, "DOCKER");
        assertThat(this.mapper.toRequest(command))
                .isEqualTo(new RuntimeTargetDiscoveryRequest("SSH", sshId, "DOCKER"));
        assertThat(this.mapper.toDomain(new RuntimeTargetCandidateResponse("forge-postgres", "DOCKER")))
                .isEqualTo(new AgentRuntimeTargetCandidate("forge-postgres", "DOCKER"));
    }

    @Test
    void mapsFullJournalModeThroughTypedAgentClientDtos() {
        final var command = new com.sitionix.forgeai.domain.model.agentproxy.SaveAgentLogSourceCommand(
                "journal", null,
                com.sitionix.forgeai.domain.model.agentproxy.AgentLogConnectionType.SSH,
                UUID.randomUUID(),
                com.sitionix.forgeai.domain.model.agentproxy.AgentLogProviderType.SYSTEMD,
                null, null, null,
                com.sitionix.forgeai.domain.model.agentproxy.AgentSystemdTargetMode.FULL_JOURNAL,
                null, null, true);

        assertThat(this.mapper.toRequest(command).systemdMode()).isEqualTo(
                com.sitionix.forgeai.domain.model.agentproxy.AgentSystemdTargetMode.FULL_JOURNAL);
    }

    @Test
    void mapsPasswordSshAuthenticationThroughTypedClientDtos() {
        final var command = new com.sitionix.forgeai.domain.model.agentproxy.CreateAgentSshConnectionCommand(
                "Ancestor",
                "192.168.0.108",
                22,
                "ancestor",
                com.sitionix.forgeai.domain.model.agentproxy.AgentSshAuthType.PASSWORD,
                null,
                "secret;$(data)");
        final var request = this.mapper.toRequest(command);
        assertThat(request.authType()).isEqualTo(
                com.sitionix.forgeai.domain.model.agentproxy.AgentSshAuthType.PASSWORD);
        assertThat(request.password()).isEqualTo("secret;$(data)");

        final var response = new com.sitionix.forgeai.infrastructure.agentclient.dto.AgentSshConnectionResponse(
                AGENT_ID,
                PROJECT_ID,
                "Ancestor",
                "192.168.0.108",
                22,
                "ancestor",
                com.sitionix.forgeai.domain.model.agentproxy.AgentSshAuthType.PASSWORD,
                CREATED,
                UPDATED);
        assertThat(this.mapper.toDomain(response).authType()).isEqualTo(
                com.sitionix.forgeai.domain.model.agentproxy.AgentSshAuthType.PASSWORD);
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
