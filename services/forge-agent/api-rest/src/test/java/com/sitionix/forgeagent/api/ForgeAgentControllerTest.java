package com.sitionix.forgeagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.api.dto.AgentListResponse;
import com.sitionix.forgeagent.api.dto.AgentResponse;
import com.sitionix.forgeagent.api.dto.CreateProjectRequest;
import com.sitionix.forgeagent.api.dto.CreateWorkflowRunRequest;
import com.sitionix.forgeagent.api.dto.CreateWorkflowRequest;
import com.sitionix.forgeagent.api.dto.NodeRunResponse;
import com.sitionix.forgeagent.api.dto.NodePositionRequest;
import com.sitionix.forgeagent.api.dto.NodePositionResponse;
import com.sitionix.forgeagent.api.dto.NodeRequest;
import com.sitionix.forgeagent.api.dto.NodeResponse;
import com.sitionix.forgeagent.api.dto.ProjectResponse;
import com.sitionix.forgeagent.api.dto.SaveAgentRequest;
import com.sitionix.forgeagent.api.dto.SaveWorkflowRequest;
import com.sitionix.forgeagent.api.dto.WorkflowRunResponse;
import com.sitionix.forgeagent.api.dto.WorkflowRunSummaryResponse;
import com.sitionix.forgeagent.api.dto.WorkflowResponse;
import com.sitionix.forgeagent.application.usecase.AgentUseCases;
import com.sitionix.forgeagent.application.usecase.CreateProjectCommand;
import com.sitionix.forgeagent.application.usecase.CreateWorkflowRunCommand;
import com.sitionix.forgeagent.application.usecase.CreateWorkflowCommand;
import com.sitionix.forgeagent.application.usecase.GetAiRuntime;
import com.sitionix.forgeagent.application.usecase.ProjectUseCases;
import com.sitionix.forgeagent.application.usecase.SaveAgentCommand;
import com.sitionix.forgeagent.application.usecase.SaveWorkflowCommand;
import com.sitionix.forgeagent.application.usecase.WorkflowRunUseCases;
import com.sitionix.forgeagent.application.usecase.WorkflowUseCases;
import com.sitionix.forgeagent.domain.model.AgentDetails;
import com.sitionix.forgeagent.domain.model.AgentListItem;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.model.Workflow;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunSummary;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ForgeAgentControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID WORKFLOW_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID NODE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID RUN_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID NODE_RUN_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");
    private static final AgentOutputSchema OUTPUT_SCHEMA = AgentOutputSchema.ofCanonicalJsonObject("{}");

    @Mock
    private ProjectUseCases projectUseCases;
    @Mock
    private AgentUseCases agentUseCases;
    @Mock
    private GetAiRuntime getAiRuntime;
    @Mock
    private WorkflowUseCases workflowUseCases;
    @Mock
    private WorkflowRunUseCases workflowRunUseCases;
    @Mock
    private ForgeAgentApiMapper mapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ForgeAgentController controller;

    @BeforeEach
    void setUp() {
        this.controller = new ForgeAgentController(
                this.projectUseCases,
                this.agentUseCases,
                this.getAiRuntime,
                this.workflowUseCases,
                this.workflowRunUseCases,
                this.mapper
        );
    }

    @Test
    void listProjects() {
        final Project project = this.project();
        final ProjectResponse response = this.projectResponse();
        when(this.projectUseCases.listProjects()).thenReturn(List.of(project));
        when(this.mapper.toResponse(project)).thenReturn(response);

        final var actual = this.controller.listProjects();

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).containsExactly(response);
        verify(this.projectUseCases).listProjects();
        verify(this.mapper).toResponse(project);
    }

    @Test
    void createProject() {
        final CreateProjectRequest request = new CreateProjectRequest("Sitionix");
        final CreateProjectCommand command = new CreateProjectCommand("Sitionix");
        final Project project = this.project();
        final ProjectResponse response = this.projectResponse();
        when(this.mapper.toCommand(request)).thenReturn(command);
        when(this.projectUseCases.createProject(command)).thenReturn(project);
        when(this.mapper.toResponse(project)).thenReturn(response);

        final var actual = this.controller.createProject(request);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(actual.getHeaders().getLocation().toString()).isEqualTo("/api/v1/projects/" + PROJECT_ID);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.mapper).toCommand(request);
        verify(this.projectUseCases).createProject(command);
        verify(this.mapper).toResponse(project);
    }

    @Test
    void listProjectAgents() {
        final AgentListItem item = new AgentListItem(AGENT_ID, PROJECT_ID, "Backend", NOW, NOW);
        final AgentListResponse response = new AgentListResponse(AGENT_ID, PROJECT_ID, "Backend", NOW, NOW);
        when(this.agentUseCases.listProjectAgents(PROJECT_ID)).thenReturn(List.of(item));
        when(this.mapper.toResponse(item)).thenReturn(response);

        final var actual = this.controller.listProjectAgents(PROJECT_ID);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).containsExactly(response);
        verify(this.agentUseCases).listProjectAgents(PROJECT_ID);
        verify(this.mapper).toResponse(item);
    }

    @Test
    void createAgent() throws Exception {
        final SaveAgentRequest request = this.agentRequest();
        final SaveAgentCommand command = this.agentCommand();
        final AgentDetails agent = this.agent();
        final AgentResponse response = this.agentResponse();
        when(this.mapper.toCommand(request)).thenReturn(command);
        when(this.agentUseCases.createAgent(PROJECT_ID, command)).thenReturn(agent);
        when(this.mapper.toResponse(agent)).thenReturn(response);

        final var actual = this.controller.createAgent(PROJECT_ID, request);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(actual.getHeaders().getLocation().toString()).isEqualTo("/api/v1/agents/" + AGENT_ID);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.mapper).toCommand(request);
        verify(this.agentUseCases).createAgent(PROJECT_ID, command);
        verify(this.mapper).toResponse(agent);
    }

    @Test
    void getAgent() throws Exception {
        final AgentDetails agent = this.agent();
        final AgentResponse response = this.agentResponse();
        when(this.agentUseCases.getAgent(AGENT_ID)).thenReturn(agent);
        when(this.mapper.toResponse(agent)).thenReturn(response);

        final var actual = this.controller.getAgent(AGENT_ID);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.agentUseCases).getAgent(AGENT_ID);
        verify(this.mapper).toResponse(agent);
    }

    @Test
    void updateAgent() throws Exception {
        final SaveAgentRequest request = this.agentRequest();
        final SaveAgentCommand command = this.agentCommand();
        final AgentDetails agent = this.agent();
        final AgentResponse response = this.agentResponse();
        when(this.mapper.toCommand(request)).thenReturn(command);
        when(this.agentUseCases.updateAgent(AGENT_ID, command)).thenReturn(agent);
        when(this.mapper.toResponse(agent)).thenReturn(response);

        final var actual = this.controller.updateAgent(AGENT_ID, request);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.mapper).toCommand(request);
        verify(this.agentUseCases).updateAgent(AGENT_ID, command);
        verify(this.mapper).toResponse(agent);
    }

    @Test
    void listProjectWorkflows() {
        final Workflow workflow = this.workflow();
        final WorkflowResponse response = this.workflowResponse();
        when(this.workflowUseCases.listProjectWorkflows(PROJECT_ID)).thenReturn(List.of(workflow));
        when(this.mapper.toResponse(workflow)).thenReturn(response);

        final var actual = this.controller.listProjectWorkflows(PROJECT_ID);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).containsExactly(response);
        verify(this.workflowUseCases).listProjectWorkflows(PROJECT_ID);
        verify(this.mapper).toResponse(workflow);
    }

    @Test
    void createWorkflow() {
        final CreateWorkflowRequest request = new CreateWorkflowRequest("Full Testing");
        final CreateWorkflowCommand command = new CreateWorkflowCommand("Full Testing");
        final Workflow workflow = this.workflow();
        final WorkflowResponse response = this.workflowResponse();
        when(this.mapper.toCommand(request)).thenReturn(command);
        when(this.workflowUseCases.createWorkflow(PROJECT_ID, command)).thenReturn(workflow);
        when(this.mapper.toResponse(workflow)).thenReturn(response);

        final var actual = this.controller.createWorkflow(PROJECT_ID, request);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(actual.getHeaders().getLocation().toString()).isEqualTo("/api/v1/workflows/" + WORKFLOW_ID);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.mapper).toCommand(request);
        verify(this.workflowUseCases).createWorkflow(PROJECT_ID, command);
        verify(this.mapper).toResponse(workflow);
    }

    @Test
    void getWorkflow() {
        final Workflow workflow = this.workflow();
        final WorkflowResponse response = this.workflowResponse();
        when(this.workflowUseCases.getWorkflow(WORKFLOW_ID)).thenReturn(workflow);
        when(this.mapper.toResponse(workflow)).thenReturn(response);

        final var actual = this.controller.getWorkflow(WORKFLOW_ID);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.workflowUseCases).getWorkflow(WORKFLOW_ID);
        verify(this.mapper).toResponse(workflow);
    }

    @Test
    void updateWorkflow() {
        final SaveWorkflowRequest request = new SaveWorkflowRequest(
                "Full Testing",
                List.of(new NodeRequest(NODE_ID, AGENT_ID, List.of(), new NodePositionRequest(1.0, 2.0)))
        );
        final SaveWorkflowCommand command = new SaveWorkflowCommand(
                "Full Testing",
                List.of(new Node(NODE_ID, AGENT_ID, List.of(), new NodePosition(1.0, 2.0)))
        );
        final Workflow workflow = this.workflow();
        final WorkflowResponse response = this.workflowResponse();
        when(this.mapper.toCommand(request)).thenReturn(command);
        when(this.workflowUseCases.updateWorkflow(WORKFLOW_ID, command)).thenReturn(workflow);
        when(this.mapper.toResponse(workflow)).thenReturn(response);

        final var actual = this.controller.updateWorkflow(WORKFLOW_ID, request);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.mapper).toCommand(request);
        verify(this.workflowUseCases).updateWorkflow(WORKFLOW_ID, command);
        verify(this.mapper).toResponse(workflow);
    }

    @Test
    void createWorkflowRun() {
        final CreateWorkflowRunRequest request = new CreateWorkflowRunRequest("Review auth changes.");
        final CreateWorkflowRunCommand command = new CreateWorkflowRunCommand("Review auth changes.");
        final WorkflowRun run = this.workflowRun();
        final WorkflowRunResponse response = this.workflowRunResponse();
        when(this.mapper.toCommand(request)).thenReturn(command);
        when(this.workflowRunUseCases.createWorkflowRun(WORKFLOW_ID, command)).thenReturn(run);
        when(this.mapper.toResponse(run)).thenReturn(response);

        final var actual = this.controller.createWorkflowRun(WORKFLOW_ID, request);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(actual.getHeaders().getLocation().toString()).isEqualTo("/api/v1/workflow-runs/" + RUN_ID);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.mapper).toCommand(request);
        verify(this.workflowRunUseCases).createWorkflowRun(WORKFLOW_ID, command);
        verify(this.mapper).toResponse(run);
    }

    @Test
    void listWorkflowRuns() {
        final WorkflowRunSummary run = new WorkflowRunSummary(RUN_ID, WORKFLOW_ID, "Full Testing", WorkflowRunStatus.QUEUED, NOW, null, null);
        final WorkflowRunSummaryResponse response = new WorkflowRunSummaryResponse(RUN_ID, WORKFLOW_ID, "Full Testing", WorkflowRunStatus.QUEUED, NOW, null, null);
        when(this.workflowRunUseCases.listWorkflowRuns(WORKFLOW_ID)).thenReturn(List.of(run));
        when(this.mapper.toSummaryResponse(run)).thenReturn(response);

        final var actual = this.controller.listWorkflowRuns(WORKFLOW_ID);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).containsExactly(response);
        verify(this.workflowRunUseCases).listWorkflowRuns(WORKFLOW_ID);
        verify(this.mapper).toSummaryResponse(run);
    }

    @Test
    void getWorkflowRun() {
        final WorkflowRun run = this.workflowRun();
        final WorkflowRunResponse response = this.workflowRunResponse();
        when(this.workflowRunUseCases.getWorkflowRun(RUN_ID)).thenReturn(run);
        when(this.mapper.toResponse(run)).thenReturn(response);

        final var actual = this.controller.getWorkflowRun(RUN_ID);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actual.getBody()).isSameAs(response);
        verify(this.workflowRunUseCases).getWorkflowRun(RUN_ID);
        verify(this.mapper).toResponse(run);
    }

    private Project project() {
        return new Project(PROJECT_ID, "Sitionix", "sitionix", NOW, NOW);
    }

    private ProjectResponse projectResponse() {
        return new ProjectResponse(PROJECT_ID, "Sitionix", NOW, NOW);
    }

    private SaveAgentRequest agentRequest() throws Exception {
        return new SaveAgentRequest("Backend", "Do work.", this.objectMapper.readTree("{}"));
    }

    private SaveAgentCommand agentCommand() {
        return new SaveAgentCommand("Backend", "Do work.", OUTPUT_SCHEMA);
    }

    private AgentDetails agent() {
        return new AgentDetails(AGENT_ID, PROJECT_ID, "Backend", "Do work.", OUTPUT_SCHEMA, NOW, NOW);
    }

    private AgentResponse agentResponse() throws Exception {
        return new AgentResponse(AGENT_ID, PROJECT_ID, "Backend", "Do work.", this.objectMapper.readTree("{}"), NOW, NOW);
    }

    private Workflow workflow() {
        return new Workflow(
                WORKFLOW_ID,
                PROJECT_ID,
                "Full Testing",
                "full testing",
                List.of(new Node(NODE_ID, AGENT_ID, List.of(), new NodePosition(1.0, 2.0))),
                NOW,
                NOW
        );
    }

    private WorkflowResponse workflowResponse() {
        return new WorkflowResponse(
                WORKFLOW_ID,
                PROJECT_ID,
                "Full Testing",
                List.of(new NodeResponse(NODE_ID, AGENT_ID, List.of(), new NodePositionResponse(1.0, 2.0))),
                NOW,
                NOW
        );
    }

    private WorkflowRun workflowRun() {
        return new WorkflowRun(
                RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                "Full Testing",
                "Review auth changes.",
                WorkflowRunStatus.QUEUED,
                List.of(new NodeRun(
                        NODE_RUN_ID,
                        NODE_ID,
                        AGENT_ID,
                        "Backend",
                        "Do work.",
                        OUTPUT_SCHEMA,
                        List.of(),
                        new NodePosition(1.0, 2.0),
                        NodeRunStatus.PENDING,
                        null,
                        null,
                        NOW,
                        null,
                        null
                )),
                NOW,
                null,
                null
        );
    }

    private WorkflowRunResponse workflowRunResponse() {
        return new WorkflowRunResponse(
                RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
                "Full Testing",
                "Review auth changes.",
                WorkflowRunStatus.QUEUED,
                List.of(new NodeRunResponse(
                        NODE_RUN_ID,
                        NODE_ID,
                        AGENT_ID,
                        "Backend",
                        "Do work.",
                        this.objectMapper.createObjectNode(),
                        List.of(),
                        new NodePositionResponse(1.0, 2.0),
                        NodeRunStatus.PENDING,
                        null,
                        null,
                        NOW,
                        null,
                        null
                )),
                NOW,
                null,
                null
        );
    }
}
