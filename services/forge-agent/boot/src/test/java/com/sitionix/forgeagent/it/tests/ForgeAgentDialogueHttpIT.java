package com.sitionix.forgeagent.it.tests;

import com.sitionix.forgeagent.application.usecase.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.model.dialogue.*;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import com.sitionix.forgeagent.domain.port.dialogue.DialogueRepository;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static com.sitionix.forgeagent.it.ForgeAgentFixtures.*;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.*;
import static org.assertj.core.api.Assertions.*;

@IntegrationTest
class ForgeAgentDialogueHttpIT extends com.sitionix.forgeagent.it.infra.AgentManagementFixture {
    @Autowired com.sitionix.forgeagent.application.dialogue.DialogueCommands commands;
    @Autowired com.sitionix.forgeagent.application.dialogue.DialogueTurnLifecycle lifecycle;
    @Autowired com.sitionix.forgeagent.application.runtime.AgentSessionLeaseService leases;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired ForgeAgentTestManager forgeIt;
    @Autowired WorkflowUseCases workflows;
    @Autowired WorkflowRunUseCases runs;
    @Autowired DialogueRepository dialogues;
    @Autowired AgentExecutionSessionRepository sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.sitionix.forgeagent.domain.port.NodeRunRepository nodeRuns;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    private static final String REPLY = "{\"message\":\"Clarified task\",\"draft\":{\"summary\":\"Task\"},\"questions\":[],\"decisions\":[],\"sources\":[],\"readyForReview\":true}";

    @Test void routesReturnPersistedHistoryAndIdempotencyStatus() throws Exception {
        NodeRun node = createNode();
        commands.initialize(node.id());
        executeCurrent(node);
        String uri = uri(node);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/workflow-runs/"+node.workflowRunId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.runtimeGraph.ports[?(@.dialogueDisposition == 'ACCEPT')].description")
                        .value(org.hamcrest.Matchers.hasItem("Reviewed result")));
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(uri))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.state").value("AWAITING_REPLY"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.messages.messages[0].role").value("ASSISTANT"))
                .andReturn().getResponse().getContentAsString();
        long revision = json.readTree(response).path("revision").longValue();
        String body = json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",revision)
                .put("text","😀 First\nsecond").toString();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri+"/messages")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri+"/messages")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(uri+"/messages?afterSequence=1&limit=1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.messages[0].text").value("😀 First\nsecond"));
    }

    @Test void validationAndOwnershipConflictsRemainTyped() throws Exception {
        NodeRun node = createNode();
        commands.initialize(node.id());
        executeCurrent(node);
        String uri = uri(node);
        String invalid = "{\"requestId\":\""+UUID.randomUUID()+"\",\"text\":\"Hello\"}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri+"/messages")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        String stale = json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",0).toString();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri+"/summary")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(stale))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("DIALOGUE_REVISION_CONFLICT"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(uri.replace(node.workflowRunId().toString(),UUID.randomUUID().toString())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }

    @Test void summaryAndCompletionUseExactPersistedRevision() throws Exception {
        NodeRun node = createNode();
        commands.initialize(node.id());
        executeCurrent(node);
        var state = commands.get(node.workflowRunId(),node.id());
        String summary = json.createObjectNode().put("requestId",UUID.randomUUID().toString())
                .put("expectedRevision",state.revision()).toString();
        for (int status : new int[]{202,200}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri(node)+"/summary")
                    .contentType("application/json").content(summary))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(status));
        }
        executeCurrent(node);
        state = commands.get(node.workflowRunId(),node.id());
        String complete = json.createObjectNode().put("requestId",UUID.randomUUID().toString())
                .put("expectedRevision",state.revision()).put("summaryRevisionId",state.summaryRevisionId().toString())
                .put("outputPortId",runs.getWorkflowRun(node.workflowRunId()).runtimeGraph().ports().stream().filter(p -> p.dialogueDisposition() == DialogueOutputDisposition.ACCEPT).findFirst().orElseThrow().sourcePortId().toString()).toString();
        for (int attempt=0;attempt<2;attempt++) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri(node)+"/complete")
                    .contentType("application/json").content(complete))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.completion.disposition").value("ACCEPT"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.latestRevision.result.draft.summary").value("Task"));
        }
    }

    private String uri(NodeRun node) { return "/api/v1/workflow-runs/"+node.workflowRunId()+"/node-runs/"+node.id()+"/dialogue"; }

    private AgentSessionExecutionClaim executeCurrent(NodeRun node) {
        var snapshot = commands.get(node.workflowRunId(),node.id());
        var request = lifecycle.claim(snapshot.activeTurn().id()).orElseThrow();
        var claim = request.executionClaim().agentSessionClaim();
        leases.persistConversation(claim,"thread-" + claim.sessionId(),"fixture-version");
        leases.persistTurn(claim,"provider-" + claim.turnId());
        assertThat(lifecycle.succeed(request,new com.sitionix.forgeagent.application.runtime.AgentExecutionResult(new NodeRunOutput(REPLY),null))).isTrue();
        assertThat(lifecycle.succeed(request,new com.sitionix.forgeagent.application.runtime.AgentExecutionResult(new NodeRunOutput(REPLY),null))).isFalse();
        return claim;
    }

    private NodeRun createNode() { return createNode(NodeType.DIALOGUE); }

    private NodeRun createNode(NodeType type) {
        forgeIt.postgresql().create().to(PROJECT.withJson("project_alpha.json")).build();
        forgeIt.postgresql().create().to(AGENT_DEFINITION.withJson("agent_a.json")).build();
        Workflow w = workflows.createWorkflow(PROJECT_ALPHA_ID, new CreateWorkflowCommand("Dialogue storage"));
        NodePort input = new NodePort(UUID.randomUUID(), "Input", "Task context", 0);
        NodePort output = new NodePort(UUID.randomUUID(), "Accept", "Reviewed result", 0, type == NodeType.DIALOGUE ? DialogueOutputDisposition.ACCEPT : null);
        Node n = new Node(UUID.randomUUID(), AGENT_A_ID, NodeInputMode.TASK_AND_DEPENDENCIES,
                List.of(input), List.of(output), new NodePosition(0, 0), NodeScopeMode.GLOBAL,
                null, null, type);
        workflows.updateWorkflow(w.id(), new SaveWorkflowCommand(w.name(), List.of(n), List.of(), input.id(), output.id()));
        return runs.getWorkflowRun(runs.createWorkflowRun(w.id(), new CreateWorkflowRunCommand("Task")).id())
                .nodeRuns().getFirst();
    }
}
