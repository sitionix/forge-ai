package com.sitionix.forgeagent.it.tests;
import com.sitionix.forgeagent.it.infra.AgentManagementFixture;
import com.sitionix.forgeagent.application.runtime.*;
import com.sitionix.forgeagent.application.standalone.StandaloneTurnWorker;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.domain.port.standalone.StandaloneSessionRepository;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

/** Actual protected HTTP/usecase/worker/SQL flow with a deterministic shared provider port. */
@IntegrationTest(properties={"forge.agent.standalone.native-subagents-enabled=false","forge.agent.worker.scheduling-enabled=false"})
class ForgeAgentStandaloneFlowIT extends AgentManagementFixture {
 @Autowired com.sitionix.forgeagent.it.infra.ForgeAgentTestManager forgeIt;
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired ProjectRepository projects;@Autowired AgentDefinitionRepository agents;
 @Autowired StandaloneTurnWorker worker;@Autowired StandaloneSessionRepository sessions;@Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
 @MockBean AgentTurnExecutionPort execution;
 static final String TOKEN="s".repeat(32);
 static {try{write("standalone-service",TOKEN);}catch(Exception e){throw new IllegalStateException(e);}}
 @DynamicPropertySource static void identity(DynamicPropertyRegistry p){p.add("server.address",()->"127.0.0.1");p.add("server.forward-headers-strategy",()->"none");p.add("forge.ai-library.service-token-file",()->ROOT.resolve("standalone-service").toString());}
 @Test void protectedSessionExecutesTwoRootTurnsAndReopensWithHistoryWithoutWorkflow()throws Exception {
  var now=Instant.now();UUID project=UUID.randomUUID(),agent=UUID.randomUUID();projects.save(new Project(project,"Standalone","standalone-"+project,now,now));
  agents.save(new AgentDefinition(agent,project,"Selected agent","selected-agent","Own root instructions",AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\"}"),new AgentModelSelection("codex","own-agent-model","high"),now,now));
  long workflowCount=jdbc.queryForObject("SELECT count(*) FROM workflow_runs",Long.class),nodeCount=jdbc.queryForObject("SELECT count(*) FROM node_runs",Long.class);
  AtomicInteger roots=new AtomicInteger(),turns=new AtomicInteger();
  when(execution.execute(any(),any())).thenAnswer(call->{
   AgentTurnExecutionRequest r=call.getArgument(0);AgentTurnEventSink sink=call.getArgument(1);
   assertThat(r.outputSchemaJson()).isNull();assertThat(r.input()).doesNotContain("__forge");assertThat(r.definition().instructions()).isEqualTo("Own root instructions");assertThat(r.definition().model().modelId()).isEqualTo("own-agent-model");
   if(r.conversationId()==null){roots.incrementAndGet();r.identities().conversationStarted("same-root","0.163.0");}else assertThat(r.conversationId()).isEqualTo("same-root");
   r.identities().dispatchTurnStart(()->{});r.identities().turnStarted("turn-"+turns.incrementAndGet());
   sink.executionEvent(new AgentExecutionEventCandidate(AgentExecutionEventType.TOOL_CALL,AgentExecutionEventStatus.COMPLETED,"tool",null,"{\"summary\":\"Actual provider fixture activity\"}",Instant.now()));
   r.identities().executionFinished(true);return new AgentTurnResult("Reply to "+r.input());
  });
  String base="/api/v1/projects/"+project+"/agent-sessions";
  var create=json.createObjectNode();create.putObject("request").put("requestId",UUID.randomUUID().toString()).put("agentSource","FORGE").put("agentId",agent.toString()).put("text","First task").putArray("repositoryIds");
  create.putObject("fallback").put("providerId","codex").put("modelId","global-must-not-win");
  var receipt=json.readTree(mvc.perform(post(base).header("Authorization","Bearer "+TOKEN).contentType("application/json").content(create.toString())).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
  UUID id=UUID.fromString(receipt.path("sessionId").asText());
  mvc.perform(post(base).header("Authorization","Bearer "+TOKEN).contentType("application/json").content(create.toString())).andExpect(status().isOk()).andExpect(jsonPath("sessionId").value(id.toString()));
  worker.poll();await().atMost(Duration.ofSeconds(10)).until(()->sessions.find(project,id).orElseThrow().status().equals("READY"));
  var first=sessions.find(project,id).orElseThrow();assertThat(first.providerConversationId()).isEqualTo("same-root");
  String message=json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",first.revision()).put("text","Follow up").toString();
  mvc.perform(post(base+"/"+id+"/messages").header("Authorization","Bearer "+TOKEN).contentType("application/json").content(message)).andExpect(status().isAccepted());
  worker.poll();await().atMost(Duration.ofSeconds(10)).until(()->sessions.find(project,id).orElseThrow().status().equals("READY"));
  mvc.perform(post(base+"/"+id+"/messages").header("Authorization","Bearer "+TOKEN).contentType("application/json").content(message)).andExpect(status().isOk());
  mvc.perform(get(base+"/"+id).header("Authorization","Bearer "+TOKEN)).andExpect(status().isOk()).andExpect(jsonPath("session.providerConversationId").value("same-root")).andExpect(jsonPath("session.definition").doesNotExist()).andExpect(jsonPath("messages.items[3].text").value("Reply to Follow up"));
  mvc.perform(get(base+"/"+id+"/events?afterSequence=1&limit=1").header("Authorization","Bearer "+TOKEN)).andExpect(status().isOk()).andExpect(jsonPath("items[0].sequence").value(2));
  mvc.perform(get(base+"/"+id)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/projects/"+UUID.randomUUID()+"/agent-sessions/"+id).header("Authorization","Bearer "+TOKEN)).andExpect(status().isNotFound());
  assertThat(roots.get()).isEqualTo(1);assertThat(turns.get()).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_runs",Long.class)).isEqualTo(workflowCount);assertThat(jdbc.queryForObject("SELECT count(*) FROM node_runs",Long.class)).isEqualTo(nodeCount);
 }
}
