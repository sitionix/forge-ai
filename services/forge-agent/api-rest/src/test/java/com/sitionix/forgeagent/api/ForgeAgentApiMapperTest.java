package com.sitionix.forgeagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.api.dto.AgentListResponse;
import com.sitionix.forgeagent.api.dto.AgentResponse;
import com.sitionix.forgeagent.api.dto.AiRuntimeResponse;
import com.sitionix.forgeagent.api.dto.CodexRuntimeEffortResponse;
import com.sitionix.forgeagent.api.dto.CodexRuntimeModelResponse;
import com.sitionix.forgeagent.api.dto.CodexRuntimeProviderResponse;
import com.sitionix.forgeagent.api.dto.CreateProjectRequest;
import com.sitionix.forgeagent.api.dto.CreateProjectTaskRequest;
import com.sitionix.forgeagent.api.dto.CreateWorkflowRunRequest;
import com.sitionix.forgeagent.api.dto.CreateWorkflowRequest;
import com.sitionix.forgeagent.api.dto.LogProviderConfigurationResponse;
import com.sitionix.forgeagent.api.dto.LogSourceRequest;
import com.sitionix.forgeagent.api.dto.LogTargetCandidateResponse;
import com.sitionix.forgeagent.api.dto.NodeRunResponse;
import com.sitionix.forgeagent.api.dto.NodePositionRequest;
import com.sitionix.forgeagent.api.dto.NodePositionResponse;
import com.sitionix.forgeagent.api.dto.NodeRequest;
import com.sitionix.forgeagent.api.dto.NodeResponse;
import com.sitionix.forgeagent.api.dto.ProjectResponse;
import com.sitionix.forgeagent.api.dto.ProjectRepositoryGitStateResponse;
import com.sitionix.forgeagent.api.dto.ProjectRepositoryResponse;
import com.sitionix.forgeagent.api.dto.ProjectTaskResponse;
import com.sitionix.forgeagent.api.dto.ProjectTaskSummaryResponse;
import com.sitionix.forgeagent.api.dto.RunConnectionResponse;
import com.sitionix.forgeagent.api.dto.RunNodeResponse;
import com.sitionix.forgeagent.api.dto.RunPortResponse;
import com.sitionix.forgeagent.api.dto.SaveAgentRequest;
import com.sitionix.forgeagent.api.dto.SaveWorkflowRequest;
import com.sitionix.forgeagent.api.dto.WorkflowRunGraphResponse;
import com.sitionix.forgeagent.api.dto.WorkflowRunResponse;
import com.sitionix.forgeagent.api.dto.WorkflowRunSummaryResponse;
import com.sitionix.forgeagent.api.dto.WorkflowConnectionRequest;
import com.sitionix.forgeagent.api.dto.WorkflowConnectionResponse;
import com.sitionix.forgeagent.api.dto.WorkflowResponse;
import com.sitionix.forgeagent.application.usecase.CreateProjectCommand;
import com.sitionix.forgeagent.application.usecase.CreateProjectTaskCommand;
import com.sitionix.forgeagent.application.usecase.CreateWorkflowRunCommand;
import com.sitionix.forgeagent.application.usecase.CreateWorkflowCommand;
import com.sitionix.forgeagent.application.usecase.SaveAgentCommand;
import com.sitionix.forgeagent.application.usecase.SaveLogSourceCommand;
import com.sitionix.forgeagent.application.usecase.SaveWorkflowCommand;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDetails;
import com.sitionix.forgeagent.domain.model.AgentListItem;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.AiRuntimeCatalog;
import com.sitionix.forgeagent.domain.model.CodexRuntimeEffort;
import com.sitionix.forgeagent.domain.model.CodexRuntimeModel;
import com.sitionix.forgeagent.domain.model.CodexRuntimeProvider;
import com.sitionix.forgeagent.domain.model.GitHeadState;
import com.sitionix.forgeagent.domain.model.GitHeadType;
import com.sitionix.forgeagent.domain.model.GitLocalRepositoryState;
import com.sitionix.forgeagent.domain.model.GitConflictState;
import com.sitionix.forgeagent.domain.model.GitOperationState;
import com.sitionix.forgeagent.domain.model.GitUpstreamRelation;
import com.sitionix.forgeagent.domain.model.GitUpstreamState;
import com.sitionix.forgeagent.domain.model.GitWorkingTreeState;
import com.sitionix.forgeagent.domain.model.DockerLogConfiguration;
import com.sitionix.forgeagent.domain.model.LogConnectionType;
import com.sitionix.forgeagent.domain.model.LogProviderType;
import com.sitionix.forgeagent.domain.model.LogSource;
import com.sitionix.forgeagent.domain.model.LogTargetCandidate;
import com.sitionix.forgeagent.domain.model.LogTargetStatus;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunFailure;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeScopeMode;
import com.sitionix.forgeagent.domain.model.PortDirection;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.model.ProjectRepositoryView;
import com.sitionix.forgeagent.domain.model.ProjectTaskDetails;
import com.sitionix.forgeagent.domain.model.ProjectTaskSummary;
import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.model.WorkflowConnection;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunGraph;
import com.sitionix.forgeagent.domain.model.WorkflowRunSummary;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.model.RuntimeProviderStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ForgeAgentApiMapperTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID WORKFLOW_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID NODE_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID NODE_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID RUN_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID NODE_RUN_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID TASK_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID REPOSITORY_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
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
    void mapsLogsApiWithoutExposingDomainConfigurationOrCandidate() {
        final LogSourceRequest request = new LogSourceRequest(
                "mission",
                null,
                LogConnectionType.LOCAL,
                null,
                LogProviderType.DOCKER,
                "mission",
                null,
                null,
                null,
                null,
                true);
        assertThat(this.mapper.toCommand(request)).isEqualTo(new SaveLogSourceCommand(
                "mission",
                null,
                LogConnectionType.LOCAL,
                null,
                LogProviderType.DOCKER,
                new DockerLogConfiguration("mission", null, null),
                true));

        final LogSource source = new LogSource(
                AGENT_ID,
                PROJECT_ID,
                "mission",
                null,
                LogConnectionType.LOCAL,
                null,
                LogProviderType.DOCKER,
                new DockerLogConfiguration("mission", null, null),
                true,
                CREATED,
                UPDATED);
        assertThat(this.mapper.toResponse(source).configuration())
                .isEqualTo(new LogProviderConfigurationResponse(
                        "mission", null, null, null, null));

        final LogTargetCandidate candidate = new LogTargetCandidate(
                "mission",
                "mission",
                LogTargetStatus.RUNNING,
                "mission:latest",
                "forge",
                "mission",
                "compose.yaml",
                false);
        assertThat(this.mapper.toResponse(candidate)).isEqualTo(new LogTargetCandidateResponse(
                "mission",
                "mission",
                LogTargetStatus.RUNNING,
                "mission:latest",
                "forge",
                "mission",
                "compose.yaml",
                false));
    }

    @Test
    void mapsAgentRequestToCommand() throws Exception {
        final SaveAgentRequest request = new SaveAgentRequest(
                "Analyzer",
                "Analyze changes.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                null
        );

        assertThat(this.mapper.toCommand(request)).isEqualTo(new SaveAgentCommand(
                "Analyzer",
                "Analyze changes.",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                null
        ));
    }

    @Test
    void rejectsOutputSchemaWithNonObjectRoot() throws Exception {
        final SaveAgentRequest request = new SaveAgentRequest("Analyzer", "Analyze changes.", this.objectMapper.readTree("[]"), null);

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
    void mapsUnclonedProjectRepositoryWithNullGitState() {
        final UUID repositoryId = UUID.fromString("88888888-8888-4888-8888-888888888888");

        assertThat(this.mapper.toResponse(new ProjectRepositoryView(repositoryId, PROJECT_ID, "service-a", false, null, CREATED)))
                .isEqualTo(new ProjectRepositoryResponse(
                        repositoryId,
                        PROJECT_ID,
                        "service-a",
                        false,
                        null,
                        CREATED
                ));
    }

    @Test
    void mapsValidProjectRepositoryGitState() {
        final UUID repositoryId = UUID.fromString("88888888-8888-4888-8888-888888888888");
        final GitLocalRepositoryState gitState = GitLocalRepositoryState.valid(
                new GitHeadState(GitHeadType.BRANCH, "main", "abcdef"),
                GitWorkingTreeState.CLEAN,
                GitConflictState.NONE,
                GitOperationState.NORMAL,
                new GitUpstreamState("origin/main", GitUpstreamRelation.BEHIND)
        );

        assertThat(this.mapper.toResponse(new ProjectRepositoryView(repositoryId, PROJECT_ID, "service-a", true, gitState, CREATED)))
                .isEqualTo(new ProjectRepositoryResponse(
                        repositoryId,
                        PROJECT_ID,
                        "service-a",
                        true,
                        new ProjectRepositoryGitStateResponse("main", "CLEAN", true),
                        CREATED
                ));
    }

    @Test
    void mapsInvalidProjectRepositoryGitState() {
        final UUID repositoryId = UUID.fromString("88888888-8888-4888-8888-888888888888");

        assertThat(this.mapper.toResponse(new ProjectRepositoryView(repositoryId, PROJECT_ID, "service-a", true, GitLocalRepositoryState.invalid(), CREATED)))
                .isEqualTo(new ProjectRepositoryResponse(
                        repositoryId,
                        PROJECT_ID,
                        "service-a",
                        true,
                        null,
                        CREATED
                ));
    }

    @Test
    void projectRepositoryResponseSerializesGitContractOnly() {
        final UUID repositoryId = UUID.fromString("88888888-8888-4888-8888-888888888888");
        final var response = new ProjectRepositoryResponse(
                repositoryId,
                PROJECT_ID,
                "service-a",
                true,
                new ProjectRepositoryGitStateResponse("main", "CLEAN", false),
                CREATED
        );

        final var responseFields = Arrays.stream(ProjectRepositoryResponse.class.getRecordComponents())
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
    void mapsProjectTaskRequestsAndResponses() throws Exception {
        assertThat(this.mapper.toCommand(new CreateProjectTaskRequest("Check calculation", "Count letters.", WORKFLOW_ID, List.of(REPOSITORY_ID))))
                .isEqualTo(new CreateProjectTaskCommand("Check calculation", "Count letters.", WORKFLOW_ID, List.of(REPOSITORY_ID)));

        final WorkflowRunSummary run = new WorkflowRunSummary(RUN_ID, WORKFLOW_ID, TASK_ID, "Full Testing", WorkflowRunStatus.QUEUED, CREATED, null, null);
        assertThat(this.mapper.toResponse(new ProjectTaskSummary(
                TASK_ID,
                PROJECT_ID,
                "Check calculation",
                WORKFLOW_ID,
                "Full Testing",
                RUN_ID,
                WorkflowRunStatus.QUEUED,
                CREATED,
                UPDATED
        ))).isEqualTo(new ProjectTaskSummaryResponse(
                TASK_ID,
                PROJECT_ID,
                "Check calculation",
                WORKFLOW_ID,
                "Full Testing",
                RUN_ID,
                WorkflowRunStatus.QUEUED,
                CREATED,
                UPDATED
        ));
        assertThat(this.mapper.toResponse(new ProjectTaskDetails(
                TASK_ID,
                PROJECT_ID,
                "Check calculation",
                "Count letters.",
                WORKFLOW_ID,
                List.of(REPOSITORY_ID),
                List.of(run),
                new NodeRunOutput("{\"answer\":\"done\"}"),
                CREATED,
                UPDATED
        ))).isEqualTo(new ProjectTaskResponse(
                TASK_ID,
                PROJECT_ID,
                "Check calculation",
                "Count letters.",
                WORKFLOW_ID,
                List.of(REPOSITORY_ID),
                List.of(new WorkflowRunSummaryResponse(RUN_ID, WORKFLOW_ID, TASK_ID, "Full Testing", WorkflowRunStatus.QUEUED, CREATED, null, null)),
                this.objectMapper.readTree("{\"answer\":\"done\"}"),
                CREATED,
                UPDATED
        ));
    }

    @Test
    void mapsAgentListItemToResponse() {
        final var item = new AgentListItem(AGENT_ID, PROJECT_ID, "Analyzer", null, CREATED, UPDATED);

        assertThat(this.mapper.toResponse(item))
                .isEqualTo(new AgentListResponse(AGENT_ID, PROJECT_ID, "Analyzer", null, CREATED, UPDATED));
    }

    @Test
    void mapsAgentDetailsToResponse() throws Exception {
        final var agent = new AgentDetails(
                AGENT_ID,
                PROJECT_ID,
                "Analyzer",
                "Analyze changes.",
                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                null,
                CREATED,
                UPDATED
        );

        assertThat(this.mapper.toResponse(agent)).isEqualTo(new AgentResponse(
                AGENT_ID,
                PROJECT_ID,
                "Analyzer",
                "Analyze changes.",
                this.objectMapper.readTree("{\"type\":\"object\"}"),
                null,
                CREATED,
                UPDATED
        ));
    }

    @Test
    void mapsRuntimeCatalogToResponse() {
        final var catalog = new AiRuntimeCatalog(List.of(new CodexRuntimeProvider(
                "codex",
                "Codex",
                RuntimeProviderStatus.READY,
                "codex/1",
                List.of(new CodexRuntimeModel(
                        "discovered-model",
                        "Discovered Model",
                        "Live model",
                        List.of(new CodexRuntimeEffort("xhigh", "Maximum reasoning"))
                ))
        )));

        assertThat(this.mapper.toResponse(catalog)).isEqualTo(new AiRuntimeResponse(List.of(new CodexRuntimeProviderResponse(
                "codex",
                "Codex",
                RuntimeProviderStatus.READY,
                "codex/1",
                List.of(new CodexRuntimeModelResponse(
                        "discovered-model",
                        "Discovered Model",
                        "Live model",
                        List.of(new CodexRuntimeEffortResponse("xhigh", "Maximum reasoning"))
                ))
        ))));
    }

    @Test
    void mapsWorkflowRequestsAndResponses() {
        assertThat(this.mapper.toCommand(new CreateWorkflowRequest("Full Testing")))
                .isEqualTo(new CreateWorkflowCommand("Full Testing"));

        final SaveWorkflowRequest saveRequest = new SaveWorkflowRequest(
                "Full Testing",
                List.of(new NodeRequest(
                        NODE_A,
                        AGENT_ID,
                        null,
                        null,
                        null,
                        new NodePositionRequest(1.0, 2.0),
                        "GLOBAL"
                ),
                        new NodeRequest(
                                NODE_B,
                                AGENT_ID,
                                null,
                                null,
                                null,
                                new NodePositionRequest(3.0, 4.0),
                                "GLOBAL"
                        )),
                List.of(new WorkflowConnectionRequest(UUID.fromString("90000000-0000-4000-8000-000000000001"), NODE_A, NODE_B)),
                NODE_A,
                NODE_B
        );
        assertThat(this.mapper.toCommand(saveRequest)).isEqualTo(new SaveWorkflowCommand(
                "Full Testing",
                List.of(new Node(
                        NODE_A,
                        AGENT_ID,
                        com.sitionix.forgeagent.domain.model.NodeInputMode.DEPENDENCIES_ONLY,
                        java.util.List.of(),
                        java.util.List.of(),
                        new NodePosition(1.0, 2.0),
                        com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
                ),
                        new Node(
                                NODE_B,
                                AGENT_ID,
                                com.sitionix.forgeagent.domain.model.NodeInputMode.DEPENDENCIES_ONLY,
                                java.util.List.of(),
                                java.util.List.of(),
                                new NodePosition(3.0, 4.0),
                                com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
                        )),
                List.of(new WorkflowConnection(UUID.fromString("90000000-0000-4000-8000-000000000001"), NODE_A, NODE_B)),
                NODE_A,
                NODE_B
        ));

        final Workflow workflow = new Workflow(
                WORKFLOW_ID,
                PROJECT_ID,
                "Full Testing",
                "full testing",
                List.of(new Node(
                        NODE_B,
                        AGENT_ID,
                        com.sitionix.forgeagent.domain.model.NodeInputMode.DEPENDENCIES_ONLY,
                        java.util.List.of(),
                        java.util.List.of(),
                        new NodePosition(3.0, 4.0),
                        com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
                )),
                List.of(new WorkflowConnection(UUID.fromString("90000000-0000-4000-8000-000000000001"), NODE_A, NODE_B)),
                NODE_A,
                NODE_B,
                CREATED,
                UPDATED
        );
        assertThat(this.mapper.toResponse(workflow)).isEqualTo(new WorkflowResponse(
                WORKFLOW_ID,
                PROJECT_ID,
                "Full Testing",
                List.of(new NodeResponse(
                        NODE_B,
                        AGENT_ID,
                        NodeInputMode.DEPENDENCIES_ONLY.name(),
                        java.util.List.of(),
                        java.util.List.of(),
                        new NodePositionResponse(3.0, 4.0),
                        "GLOBAL"
                )),
                List.of(new WorkflowConnectionResponse(UUID.fromString("90000000-0000-4000-8000-000000000001"), NODE_A, NODE_B)),
                NODE_A,
                NODE_B,
                CREATED,
                UPDATED
        ));
    }

    @Test
    void workflowNodeRequestRequiresValidScopeMode() {
        assertThatThrownBy(() -> this.mapper.toCommand(new SaveWorkflowRequest(
                "Full Testing",
                List.of(new NodeRequest(NODE_A, AGENT_ID, NodeInputMode.DEPENDENCIES_ONLY.name(),
                        List.of(), List.of(), new NodePositionRequest(1.0, 2.0), null)),
                List.of(),
                NODE_A,
                NODE_A
        )))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("INVALID_NODE_SCOPE_MODE");

        assertThatThrownBy(() -> this.mapper.toCommand(new SaveWorkflowRequest(
                "Full Testing",
                List.of(new NodeRequest(NODE_A, AGENT_ID, NodeInputMode.DEPENDENCIES_ONLY.name(),
                        List.of(), List.of(), new NodePositionRequest(1.0, 2.0), "repository")),
                List.of(),
                NODE_A,
                NODE_A
        )))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("INVALID_NODE_SCOPE_MODE");

        assertThat(this.mapper.toCommand(new SaveWorkflowRequest(
                "Full Testing",
                List.of(new NodeRequest(NODE_A, AGENT_ID, NodeInputMode.DEPENDENCIES_ONLY.name(),
                        List.of(), List.of(), new NodePositionRequest(1.0, 2.0), "PER_SCOPE")),
                List.of(),
                NODE_A,
                NODE_A
        )).nodes()).singleElement()
                .extracting(Node::scopeMode)
                .isEqualTo(NodeScopeMode.PER_SCOPE);
    }

    @Test
    void mapsWorkflowRunRequestsAndResponses() throws Exception {
        assertThat(this.mapper.toCommand(new CreateWorkflowRunRequest("Review auth changes.")))
                .isEqualTo(new CreateWorkflowRunCommand("Review auth changes."));

        final WorkflowRun run = new WorkflowRun(
                RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                "Review auth changes.",
                WorkflowRunStatus.QUEUED,
                List.of(new NodeRun(
                        NODE_RUN_ID,
                        RUN_ID,
                        NODE_A,
                        AGENT_ID,
                        "Analyzer",
                        "Analyze changes.",
                        AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                        NodeInputMode.DEPENDENCIES_ONLY,
                        new NodePosition(1.0, 2.0),
                        UUID.fromString("99999999-0000-4000-8000-000000000001"),
                        null,
                        null,
                        null,
                        null,
                        NodeRunStatus.PENDING,
                        new NodeRunOutput("{\"summary\":\"done\"}"),
                        new NodeRunFailure("ERR", "Failed"),
                        null,
                        CREATED,
                        null,
                        null,
                        null
                )),
                List.of(),
                List.of(),
                null,
                new NodeRunOutput("{\"task\":\"result\"}"),
                NODE_RUN_ID,
                CREATED,
                null,
                null,
                java.util.List.of()
        );

        assertThat(this.mapper.toSummaryResponse(new WorkflowRunSummary(
                RUN_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                WorkflowRunStatus.QUEUED,
                CREATED,
                null,
                null
        ))).isEqualTo(new WorkflowRunSummaryResponse(
                RUN_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                WorkflowRunStatus.QUEUED,
                CREATED,
                null,
                null
        ));
        assertThat(this.mapper.toResponse(run)).isEqualTo(new WorkflowRunResponse(
                RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                "Review auth changes.",
                WorkflowRunStatus.QUEUED,
                List.of(new NodeRunResponse(
                        NODE_RUN_ID,
                        NODE_A,
                        AGENT_ID,
                        "Analyzer",
                        "Analyze changes.",
                        this.objectMapper.readTree("{\"type\":\"object\"}"),
                        NodeInputMode.DEPENDENCIES_ONLY.name(),
                        new NodePositionResponse(1.0, 2.0),
                        UUID.fromString("99999999-0000-4000-8000-000000000001"),
                        null,
                        null,
                        null,
                        NodeRunStatus.PENDING,
                        this.objectMapper.readTree("{\"summary\":\"done\"}"),
                        new com.sitionix.forgeagent.api.dto.NodeRunFailureResponse("ERR", "Failed"),
                        CREATED,
                        null,
                        null,
                        null
                )),
                List.of(),
                List.of(),
                null,
                this.objectMapper.readTree("{\"task\":\"result\"}"),
                NODE_RUN_ID,
                CREATED,
                null,
                null,
                java.util.List.of()
        ));
    }

    @Test
    void mapsRuntimeGraphSnapshot() throws Exception {
        final UUID inputPortId = UUID.fromString("77777777-7777-4777-8777-777777777771");
        final UUID outputPortId = UUID.fromString("77777777-7777-4777-8777-777777777772");
        final UUID connectionId = UUID.fromString("77777777-7777-4777-8777-777777777773");
        final Instant routingCompletedAt = Instant.parse("2026-08-04T00:02:00Z");
        final WorkflowRun run = new WorkflowRun(
                RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                null,
                "Full Testing",
                "Review auth changes.",
                WorkflowRunStatus.RUNNING,
                List.of(new NodeRun(
                        NODE_RUN_ID,
                        RUN_ID,
                        NODE_A,
                        AGENT_ID,
                        "Analyzer",
                        "Analyze changes.",
                        AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                        NodeInputMode.DEPENDENCIES_ONLY,
                        new NodePosition(1.0, 2.0),
                        UUID.fromString("99999999-0000-4000-8000-000000000001"),
                        inputPortId,
                        null,
                        outputPortId,
                        routingCompletedAt,
                        NodeRunStatus.SUCCEEDED,
                        new NodeRunOutput("{\"summary\":\"done\"}"),
                        null,
                        null,
                        CREATED,
                        CREATED,
                        routingCompletedAt,
                        null
                )),
                List.of(),
                List.of(),
                new WorkflowRunGraph(
                        RUN_ID,
                        inputPortId,
                        outputPortId,
                        List.of(new RunNode(
                                RUN_ID,
                                NODE_A,
                                AGENT_ID,
                                "Analyzer",
                                "Analyze changes.",
                                AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),
                                null,
                                NodeInputMode.DEPENDENCIES_ONLY,
                                new NodePosition(1.0, 2.0),
                                com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
                        )),
                        List.of(
                                new RunPort(RUN_ID, inputPortId, NODE_A, PortDirection.INPUT, "Initial", "Initial input.", 0),
                                new RunPort(RUN_ID, outputPortId, NODE_A, PortDirection.OUTPUT, "Done", "Terminal output.", 0)
                        ),
                        List.of(new RunConnection(RUN_ID, connectionId, outputPortId, inputPortId))
                ),
                null,
                null,
                CREATED,
                CREATED,
                null,
                java.util.List.of()
        );

        final WorkflowRunResponse response = this.mapper.toResponse(run);

        assertThat(response.runtimeGraph()).isEqualTo(new WorkflowRunGraphResponse(
                inputPortId,
                outputPortId,
                List.of(new RunNodeResponse(
                        NODE_A,
                        "Analyzer",
                        new NodePositionResponse(1.0, 2.0),
                        "GLOBAL"
                )),
                List.of(
                        new RunPortResponse(inputPortId, NODE_A, PortDirection.INPUT, "Initial", 0),
                        new RunPortResponse(outputPortId, NODE_A, PortDirection.OUTPUT, "Done", 0)
                ),
                List.of(new RunConnectionResponse(connectionId, outputPortId, inputPortId))
        ));
    }
}
