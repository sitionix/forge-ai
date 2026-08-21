package com.sitionix.forgeai.api.agentproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentConnectionResolution;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunFailure;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunOutputDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunStatus;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepository;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepositoryGitState;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectRepositoryWorkingTreeState;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTask;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProjectTaskSummary;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeCatalog;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeEffort;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeModel;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeProvider;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeProviderStatus;
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
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentProxyApiMapperTest {

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
    private final AgentProxyApiMapper mapper = new AgentProxyApiMapper(this.objectMapper);

    @Test
    void mapsProjectRequestToCommand() {
        assertThat(this.mapper.toCommand(new AgentProjectRequest("Sitionix")))
                .isEqualTo(new CreateAgentProjectCommand("Sitionix"));
    }

    @Test
    void mapsProjectTaskRequestsAndResponses() throws Exception {
        final var repositoryIds = List.of(UUID.fromString("55555555-5555-4555-8555-555555555555"));
        assertThat(this.mapper.toCommand(new CreateAgentProjectTaskRequest("Check calculation", "Count letters.", WORKFLOW_ID, repositoryIds)))
                .isEqualTo(new CreateAgentProjectTaskCommand("Check calculation", "Count letters.", WORKFLOW_ID, repositoryIds));

        final var summary = new AgentProjectTaskSummary(TASK_ID, PROJECT_ID, "Check calculation", WORKFLOW_ID, "Full Testing", RUN_ID, AgentWorkflowRunStatus.QUEUED, CREATED, UPDATED);
        assertThat(this.mapper.toResponse(summary)).isEqualTo(new AgentProjectTaskSummaryResponse(
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

        final var task = new AgentProjectTask(
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
        );
        assertThat(this.mapper.toResponse(task)).isEqualTo(new AgentProjectTaskResponse(
                TASK_ID,
                PROJECT_ID,
                "Check calculation",
                "Count letters.",
                WORKFLOW_ID,
                repositoryIds,
                List.of(new AgentWorkflowRunSummaryResponse(RUN_ID, WORKFLOW_ID, TASK_ID, "Full Testing", AgentWorkflowRunStatus.QUEUED, CREATED, null, null)),
                this.objectMapper.readTree("{\"answer\":\"done\"}"),
                CREATED,
                UPDATED
        ));
    }

    @Test
    void mapsAgentRequestToCommand() throws Exception {
        final var request = new AgentDefinitionRequest("Backend", "Do work.", this.objectMapper.readTree("{\"type\":\"object\"}"), null);

        assertThat(this.mapper.toCommand(request)).isEqualTo(new SaveAgentDefinitionCommand(
                "Backend",
                "Do work.",
                new AgentOutputSchemaDocument("{\"type\":\"object\"}"),
                null
        ));
    }

    @Test
    void rejectsNonObjectOutputSchemaAsLocalInvalidRequest() throws Exception {
        final var request = new AgentDefinitionRequest("Backend", "Do work.", this.objectMapper.readTree("[]"), null);

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
    void mapsProjectRepositoryToTypedResponse() {
        final UUID repositoryId = UUID.fromString("88888888-8888-4888-8888-888888888888");

        assertThat(this.mapper.toResponse(new AgentProjectRepository(
                repositoryId,
                PROJECT_ID,
                "service-a",
                true,
                new AgentProjectRepositoryGitState(
                        null,
                        AgentProjectRepositoryWorkingTreeState.CLEAN,
                        false
                ),
                CREATED
        ))).isEqualTo(new AgentProjectRepositoryResponse(
                repositoryId,
                PROJECT_ID,
                "service-a",
                true,
                new AgentProjectRepositoryGitStateResponse(null, "CLEAN", false),
                CREATED
        ));
    }

    @Test
    void projectRepositoryResponseSerializesGitContractOnly() {
        final UUID repositoryId = UUID.fromString("88888888-8888-4888-8888-888888888888");
        final var response = new AgentProjectRepositoryResponse(
                repositoryId,
                PROJECT_ID,
                "service-a",
                true,
                new AgentProjectRepositoryGitStateResponse("main", "CLEAN", false),
                CREATED
        );

        final var responseFields = Arrays.stream(AgentProjectRepositoryResponse.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();
        final var json = this.objectMapper.valueToTree(response.git());

        assertThat(responseFields).containsExactly("id", "projectId", "name", "cloned", "git", "createdAt");
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.has("branch")).isTrue();
        assertThat(json.has("workingTree")).isTrue();
        assertThat(json.has("pullAvailable")).isTrue();
        assertThat(json.has("cloned")).isFalse();
        assertThat(json.has("valid")).isFalse();
        assertThat(json.has("head")).isFalse();
        assertThat(json.has("upstream")).isFalse();
        assertThat(json.has("conflictState")).isFalse();
        assertThat(json.has("operationState")).isFalse();
        assertThat(json.has("blockedReason")).isFalse();
    }

    @Test
    void mapsAgentListItemToTypedResponse() {
        assertThat(this.mapper.toResponse(new AgentDefinitionListItem(AGENT_ID, PROJECT_ID, "Backend", null, CREATED, UPDATED)))
                .isEqualTo(new AgentDefinitionListResponse(AGENT_ID, PROJECT_ID, "Backend", null, CREATED, UPDATED));
    }

    @Test
    void mapsAgentDetailsToTypedResponse() throws Exception {
        final var agent = new AgentDefinitionDetails(
                AGENT_ID,
                PROJECT_ID,
                "Backend",
                "Do work.",
                new AgentOutputSchemaDocument("{\"type\":\"object\"}"),
                null,
                CREATED,
                UPDATED
        );

        assertThat(this.mapper.toResponse(agent)).isEqualTo(new AgentDefinitionResponse(
                AGENT_ID,
                PROJECT_ID,
                "Backend",
                "Do work.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                null,
                CREATED,
                UPDATED
        ));
    }

    @Test
    void mapsRuntimeCatalogToTypedResponse() {
        final var runtime = new AgentRuntimeCatalog(List.of(new AgentRuntimeProvider(
                "codex",
                "Codex",
                AgentRuntimeProviderStatus.READY,
                "codex/1",
                List.of(new AgentRuntimeModel(
                        "discovered-model",
                        "Discovered Model",
                        "Live model",
                        List.of(new AgentRuntimeEffort("xhigh", "Maximum reasoning"))
                ))
        )));

        assertThat(this.mapper.toResponse(runtime)).isEqualTo(new AgentRuntimeResponse(List.of(new AgentRuntimeProviderResponse(
                "codex",
                "Codex",
                AgentRuntimeProviderStatus.READY,
                "codex/1",
                List.of(new AgentRuntimeModelResponse(
                        "discovered-model",
                        "Discovered Model",
                        "Live model",
                        List.of(new AgentRuntimeEffortResponse("xhigh", "Maximum reasoning"))
                ))
        ))));
    }

    @Test
    void mapsWorkflowRequestsAndResponses() {
        assertThat(this.mapper.toCommand(new AgentWorkflowRequest("Full Testing")))
                .isEqualTo(new CreateAgentWorkflowCommand("Full Testing"));

        final var inputRequest = new NodePortRequest(INPUT_ID, "Review feedback", "Feedback produced by review.", 0);
        final var outputRequest = new NodePortRequest(OUTPUT_ID, "Approved", "Continue when accepted.", 0);
        final var connectionRequest = new WorkflowConnectionRequest(CONNECTION_ID, OUTPUT_ID, INPUT_ID);
        final var input = new NodePort(INPUT_ID, "Review feedback", "Feedback produced by review.", 0);
        final var output = new NodePort(OUTPUT_ID, "Approved", "Continue when accepted.", 0);
        final var connection = new WorkflowConnection(CONNECTION_ID, OUTPUT_ID, INPUT_ID);
        final var nodeRequest = new NodeRequest(
                NODE_ID,
                AGENT_ID,
                "opaque-node-run-mode",
                List.of(inputRequest),
                List.of(outputRequest),
                new NodePositionRequest(1.0, 2.0),
                "GLOBAL"
        );
        assertThat(this.mapper.toCommand(new SaveAgentWorkflowRequest("Full Testing", List.of(nodeRequest), List.of(connectionRequest), INPUT_ID, OUTPUT_ID)))
                .isEqualTo(new SaveAgentWorkflowCommand(
                        "Full Testing",
                        List.of(new Node(
                                NODE_ID,
                                AGENT_ID,
                                "opaque-node-run-mode",
                                List.of(input),
                                List.of(output),
                                new NodePosition(1.0, 2.0),
                                "GLOBAL"
                        )),
                        List.of(connection),
                        INPUT_ID,
                        OUTPUT_ID
                ));

        final var workflow = new AgentWorkflow(
                WORKFLOW_ID,
                PROJECT_ID,
                "Full Testing",
                List.of(new Node(
                        NODE_ID,
                        AGENT_ID,
                        "opaque-input-mode",
                        List.of(input),
                        List.of(output),
                        new NodePosition(1.0, 2.0),
                        "GLOBAL"
                )),
                List.of(connection),
                INPUT_ID,
                OUTPUT_ID,
                CREATED,
                UPDATED
        );
        assertThat(this.mapper.toResponse(workflow)).isEqualTo(new AgentWorkflowResponse(
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
        ));
    }

    @Test
    void mapsWorkflowRunRequestsAndResponses() throws Exception {
        assertThat(this.mapper.toCommand(new CreateAgentWorkflowRunRequest("Review auth changes.")))
                .isEqualTo(new CreateAgentWorkflowRunCommand("Review auth changes."));

        assertThat(this.mapper.toResponse(new AgentWorkflowRunSummary(
                RUN_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                AgentWorkflowRunStatus.QUEUED,
                CREATED,
                null,
                null
        ))).isEqualTo(new AgentWorkflowRunSummaryResponse(
                RUN_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                AgentWorkflowRunStatus.QUEUED,
                CREATED,
                null,
                null
        ));

        final var run = new AgentWorkflowRun(
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
                        UUID.fromString("99999999-0000-4000-8000-000000000001"),
                        null,
                        null,
                        null,
                        AgentNodeRunStatus.PENDING,
                        new AgentNodeRunOutputDocument("{\"summary\":\"done\"}"),
                        new AgentNodeRunFailure("ERR", "Failed"),
                        CREATED,
                        null,
                        null,
                        null
                )),
                List.of(new AgentConnectionResolution(
                        UUID.fromString("77777777-0000-4000-8000-000000000001"),
                        UUID.fromString("99999999-0000-4000-8000-000000000001"),
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
                new AgentWorkflowRunGraph(
                        INPUT_ID,
                        OUTPUT_ID,
                        List.of(new AgentRunNode(
                                NODE_ID,
                                "Analyzer",
                                new NodePosition(1.0, 2.0),
                                "GLOBAL"
                        )),
                        List.of(new AgentRunPort(INPUT_ID, NODE_ID, "INPUT", "Initial", 0),
                                new AgentRunPort(OUTPUT_ID, NODE_ID, "OUTPUT", "Done", 0)),
                        List.of(new AgentRunConnection(CONNECTION_ID, OUTPUT_ID, INPUT_ID))
                ),
                new AgentNodeRunOutputDocument("{\"task\":\"result\"}"),
                NODE_RUN_ID,
                CREATED,
                null,
                null,
                java.util.List.of()
        );
        assertThat(this.mapper.toResponse(run)).isEqualTo(new AgentWorkflowRunResponse(
                RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                "Review auth changes.",
                AgentWorkflowRunStatus.QUEUED,
                List.of(new AgentNodeRunResponse(
                        NODE_RUN_ID,
                        NODE_ID,
                        AGENT_ID,
                        "Analyzer",
                        "Analyze changes.",
                        this.objectMapper.readTree("{\"type\":\"object\"}"),
                        "opaque-input-mode",
                        new NodePositionResponse(1.0, 2.0),
                        UUID.fromString("99999999-0000-4000-8000-000000000001"),
                        null,
                        null,
                        null,
                        AgentNodeRunStatus.PENDING,
                        this.objectMapper.readTree("{\"summary\":\"done\"}"),
                        new AgentNodeRunFailureResponse("ERR", "Failed"),
                        CREATED,
                        null,
                        null,
                        null
                )),
                List.of(new AgentConnectionResolutionResponse(
                        UUID.fromString("77777777-0000-4000-8000-000000000001"),
                        UUID.fromString("99999999-0000-4000-8000-000000000001"),
                        NODE_RUN_ID,
                        CONNECTION_ID,
                        INPUT_ID,
                        ConnectionResolutionType.DELIVERED,
                        this.objectMapper.readTree("{\"summary\":\"done\"}"),
                        null,
                        CREATED,
                        REPOSITORY_ID
                )),
                List.of(),
                new AgentWorkflowRunGraphResponse(
                        INPUT_ID,
                        OUTPUT_ID,
                        List.of(new AgentRunNodeResponse(
                                NODE_ID,
                                "Analyzer",
                                new NodePositionResponse(1.0, 2.0),
                                "GLOBAL"
                        )),
                        List.of(new AgentRunPortResponse(INPUT_ID, NODE_ID, "INPUT", "Initial", 0),
                                new AgentRunPortResponse(OUTPUT_ID, NODE_ID, "OUTPUT", "Done", 0)),
                        List.of(new AgentRunConnectionResponse(CONNECTION_ID, OUTPUT_ID, INPUT_ID))
                ),
                this.objectMapper.readTree("{\"task\":\"result\"}"),
                NODE_RUN_ID,
                CREATED,
                null,
                null,
                java.util.List.of()
        ));
    }

    @Test
    void workflowNodeRequestPreservesOpaqueSemanticFieldsAndNullPosition() {
        final var command = this.mapper.toCommand(new SaveAgentWorkflowRequest(
                "Full Testing",
                List.of(new NodeRequest(NODE_ID, AGENT_ID, null,
                        List.of(), List.of(), null, "repository")),
                List.of(),
                INPUT_ID,
                OUTPUT_ID
        ));

        assertThat(command.nodes().getFirst().inputMode()).isNull();
        assertThat(command.nodes().getFirst().position()).isNull();
        assertThat(command.nodes().getFirst().scopeMode()).isEqualTo("repository");
    }
}
