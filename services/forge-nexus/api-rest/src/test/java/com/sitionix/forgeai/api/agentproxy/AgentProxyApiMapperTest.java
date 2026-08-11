package com.sitionix.forgeai.api.agentproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionDetails;
import com.sitionix.forgeai.domain.model.agentproxy.AgentDefinitionListItem;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunFailure;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunOutputDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunStatus;
import com.sitionix.forgeai.domain.model.agentproxy.AgentOutputSchemaDocument;
import com.sitionix.forgeai.domain.model.agentproxy.AgentProject;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeCatalog;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeEffort;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeModel;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeProvider;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeProviderStatus;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflow;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRun;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunStatus;
import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunSummary;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentProjectCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowCommand;
import com.sitionix.forgeai.domain.model.agentproxy.CreateAgentWorkflowRunCommand;
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
    private static final UUID RUN_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID NODE_RUN_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
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

    @Test
    void mapsWorkflowRunRequestsAndResponses() throws Exception {
        assertThat(this.mapper.toCommand(new CreateAgentWorkflowRunRequest("Review auth changes.")))
                .isEqualTo(new CreateAgentWorkflowRunCommand("Review auth changes."));

        assertThat(this.mapper.toResponse(new AgentWorkflowRunSummary(
                RUN_ID,
                WORKFLOW_ID,
                "Full Testing",
                AgentWorkflowRunStatus.QUEUED,
                CREATED,
                null,
                null
        ))).isEqualTo(new AgentWorkflowRunSummaryResponse(
                RUN_ID,
                WORKFLOW_ID,
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
                        List.of(),
                        new NodePosition(1.0, 2.0),
                        AgentNodeRunStatus.PENDING,
                        new AgentNodeRunOutputDocument("{\"summary\":\"done\"}"),
                        new AgentNodeRunFailure("ERR", "Failed"),
                        CREATED,
                        null,
                        null
                )),
                CREATED,
                null,
                null
        );
        assertThat(this.mapper.toResponse(run)).isEqualTo(new AgentWorkflowRunResponse(
                RUN_ID,
                PROJECT_ID,
                WORKFLOW_ID,
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
                        List.of(),
                        new NodePositionResponse(1.0, 2.0),
                        AgentNodeRunStatus.PENDING,
                        this.objectMapper.readTree("{\"summary\":\"done\"}"),
                        new AgentNodeRunFailureResponse("ERR", "Failed"),
                        CREATED,
                        null,
                        null
                )),
                CREATED,
                null,
                null
        ));
    }
}
