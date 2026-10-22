package com.sitionix.forgeagent.it.tests;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.model.execution.EffectiveAgentDefinition;
import com.sitionix.forgeagent.domain.model.standalone.*;
import com.sitionix.forgeagent.domain.port.standalone.StandaloneSessionRepository;
import com.sitionix.forgeagent.infrastructure.postgres.adapter.standalone.PostgresStandaloneSessionRepository;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

class ForgeAgentStandaloneStorageIT {
 private static PostgreSQLContainer<?> database;private static JdbcTemplate jdbc;private static StandaloneSessionRepository sessions;
 private UUID project;
 @BeforeAll static void start(){
  database=new PostgreSQLContainer<>("postgres:16-alpine");database.start();
  var source=new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword());Flyway.configure().dataSource(source).load().migrate();jdbc=new JdbcTemplate(source);
  var target=new PostgresStandaloneSessionRepository(jdbc,new ObjectMapper().findAndRegisterModules(),source);
  var proxy=new ProxyFactory(target);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));sessions=(StandaloneSessionRepository)proxy.getProxy();
 }
 @AfterAll static void stop(){if(database!=null)database.stop();}
 @AfterEach void clearProject(){jdbc.update("DELETE FROM agent_projects WHERE id=?",project);}
 @BeforeEach void project(){project=UUID.randomUUID();jdbc.update("INSERT INTO agent_projects(id,name,normalized_name,created_at,updated_at)VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",project,"Session project",project.toString());}
 private StandaloneAgentSession value(){var now=Instant.now();return new StandaloneAgentSession(UUID.randomUUID(),project,new EffectiveAgentDefinition("FORGE",UUID.randomUUID().toString(),"Selected agent","private instructions",new AgentModelSelection("codex","agent-model","high"),"snapshot",Map.of(),false),List.of(),"workspace",null,null,"CREATED",0,null,null,now,now,now);}
 @Test void durableSessionAndTwoTurnsNeedNoWorkflowAndKeepImmutableIdentity(){
  var s=value();UUID request=UUID.randomUUID();var created=sessions.create(s,request,"hash","first message");
  assertThat(sessions.create(value(),request,"hash","first message").sessionId()).isEqualTo(s.id());
  assertThatThrownBy(()->sessions.create(value(),request,"changed","other")).hasMessageContaining("different input");
  var c=sessions.claim("owner",600).orElseThrow();assertThat(c.session().id()).isEqualTo(s.id());
  assertThat(sessions.claim("other",600)).isEmpty();
  assertThat(sessions.persistConversation(c,"root-thread","0.163.0")).isTrue();assertThat(sessions.persistTurn(c,"first-turn")).isTrue();
  var written=new java.util.concurrent.atomic.AtomicBoolean();sessions.dispatch(c,()->written.set(true));assertThat(written).isTrue();
  var event=new AgentExecutionEventCandidate(AgentExecutionEventType.TOOL_CALL,AgentExecutionEventStatus.COMPLETED,"subagent","event1","{\"tool\":\"spawnAgent\"}",Instant.now());
  assertThat(sessions.appendEvent(c,event)).isTrue();assertThat(sessions.appendEvent(c,event)).isTrue();assertThat(sessions.events(project,s.id(),0,100)).hasSize(1);
  assertThat(sessions.finish(c,"SUCCEEDED","first reply",null,false)).isTrue();
  var ready=sessions.find(project,s.id()).orElseThrow();assertThat(ready.status()).isEqualTo("READY");assertThat(ready.providerConversationId()).isEqualTo("root-thread");
  var next=sessions.send(project,s.id(),UUID.randomUUID(),ready.revision(),"follow-up");var resumed=sessions.claim("second-owner",600).orElseThrow();
  assertThat(resumed.session().providerConversationId()).isEqualTo("root-thread");assertThat(sessions.persistTurn(resumed,"second-turn")).isTrue();assertThat(sessions.finish(resumed,"SUCCEEDED","second reply",null,false)).isTrue();
  assertThat(sessions.messages(project,s.id(),0,100)).extracting(StandaloneAgentMessage::text).containsExactly("first message","first reply","follow-up","second reply");
  assertThat(sessions.messages(project,s.id(),2,1)).extracting(StandaloneAgentMessage::sequence).containsExactly(3L);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_runs",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM node_runs",Long.class)).isZero();
  assertThatThrownBy(()->jdbc.update("UPDATE standalone_agent_sessions SET definition_snapshot='{}'::jsonb WHERE id=?",s.id())).hasMessageContaining("immutable");
  assertThat(sessions.find(UUID.randomUUID(),s.id())).isEmpty();
 }
 @Test void duplicateSendBeforeRevisionAndStaleLeaseCannotWriteOrDispatch(){
  var s=value();sessions.create(s,UUID.randomUUID(),"hash","hello");var c=sessions.claim("owner",600).orElseThrow();sessions.finish(c,"SUCCEEDED","reply",null,false);
  var ready=sessions.find(project,s.id()).orElseThrow();UUID request=UUID.randomUUID();var accepted=sessions.send(project,s.id(),request,ready.revision(),"again");
  assertThat(sessions.send(project,s.id(),request,0,"again").turnId()).isEqualTo(accepted.turnId());
  assertThatThrownBy(()->sessions.send(project,s.id(),UUID.randomUUID(),ready.revision()+1,"parallel")).hasMessageContaining("active");
  var next=sessions.claim("new",600).orElseThrow();jdbc.update("UPDATE standalone_agent_turns SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",next.turnId());
  assertThat(sessions.renew(next)).isFalse();assertThat(sessions.persistTurn(next,"late")).isFalse();
  assertThatThrownBy(()->sessions.dispatch(next,()->fail("stale request dispatched"))).hasMessageContaining("ownership");
  assertThat(sessions.quarantine(next,"SESSION_RECOVERY_REQUIRED")).isTrue();assertThat(sessions.claim("other",600)).isEmpty();
  assertThat(sessions.find(project,s.id()).orElseThrow().status()).isEqualTo("RECOVERY_REQUIRED");
 }
 @Test void cleanupProofGatesCompletionAndRecoveryBeforeAndAfterDispatch(){
  for(boolean dispatched:List.of(false,true)){
   var s=value();sessions.create(s,UUID.randomUUID(),"hash","task");var c=sessions.claim("owner",600).orElseThrow();
   assertThat(sessions.runtimeStarted(c)).isTrue();if(dispatched)sessions.dispatch(c,()->{});
   assertThat(sessions.finish(c,"SUCCEEDED","reply",null,false)).isFalse();
   assertThat(sessions.finish(c,"FAILED",null,"SESSION_RUNTIME_CLEANUP_REQUIRED",true)).isTrue();
   assertThat(sessions.claimRecovery("recovery")).isEmpty();
   assertThatThrownBy(()->sessions.send(project,s.id(),UUID.randomUUID(),sessions.find(project,s.id()).orElseThrow().revision(),"unsafe new turn")).hasMessageContaining("active");
   assertThat(sessions.runtimeCleaned(c)).isTrue();var recovery=sessions.claimRecovery("recovery").orElseThrow();
   assertThat(sessions.reconcileRecovery(recovery,"FAILED",null,"SESSION_EXECUTION_INTERRUPTED")).isTrue();
   assertThat(sessions.reconcileRecovery(recovery,"SUCCEEDED","duplicate",null)).isFalse();
   assertThat(sessions.find(project,s.id()).orElseThrow().status()).isEqualTo("READY");
  }
 }
 @Test void restartWithUnprovenCleanupRemainsManualAndStaleProofCannotUnlockSession(){
  var s=value();sessions.create(s,UUID.randomUUID(),"hash","task");var c=sessions.claim("old-process",600).orElseThrow();assertThat(sessions.runtimeStarted(c)).isTrue();
  jdbc.update("UPDATE standalone_agent_turns SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",c.turnId());
  assertThat(sessions.quarantine(c,"SESSION_RECOVERY_REQUIRED")).isTrue();assertThat(sessions.runtimeCleaned(c)).isFalse();
  assertThat(sessions.claimRecovery("new-process")).isEmpty();assertThat(sessions.find(project,s.id()).orElseThrow().status()).isEqualTo("RECOVERY_REQUIRED");
 }
 @Test void queuedCancellationIsIdempotentAndConversationCanBeUsedAgain(){
  var s=value();sessions.create(s,UUID.randomUUID(),"hash","hello");var started=sessions.find(project,s.id()).orElseThrow();UUID request=UUID.randomUUID();
  var stopped=sessions.cancel(project,s.id(),request,started.revision());assertThat(sessions.cancel(project,s.id(),request,0).accepted()).isFalse();
  assertThat(sessions.find(project,s.id()).orElseThrow().status()).isEqualTo("READY");assertThat(sessions.claim("worker",600)).isEmpty();
  assertThat(sessions.send(project,s.id(),UUID.randomUUID(),stopped.revision(),"new task").accepted()).isTrue();
 }
}
