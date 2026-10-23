package com.sitionix.forgeagent.application;
import com.sitionix.forgeagent.application.definition.AgentDefinitionResolver;
import com.sitionix.forgeagent.application.runtime.*;
import com.sitionix.forgeagent.application.standalone.*;
import com.sitionix.forgeagent.application.mcp.McpExecutionSelectionService;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.model.execution.EffectiveAgentDefinition;
import com.sitionix.forgeagent.domain.model.standalone.*;
import com.sitionix.forgeagent.domain.port.standalone.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
class StandaloneSessionRecoveryTest {
 @Test void reconcilesExactlyOnePersistedReplyWithoutStartingAnotherTurn(){
  var repo=mock(StandaloneSessionRepository.class);var inspector=mock(AgentExecutionRecoveryInspector.class);var spaces=mock(ExecutionWorkspaceResolver.class);var definitions=mock(AgentDefinitionResolver.class);var gateway=mock(com.sitionix.forgeagent.application.mcp.McpGatewayService.class);var mcp=new McpExecutionSelectionService(mock(com.sitionix.forgeagent.domain.port.WorkflowRunRepository.class),mock(com.sitionix.forgeagent.domain.port.McpConnectionRepository.class),java.util.UUID::randomUUID,gateway);
  var space=new ExecutionWorkspace(Path.of("/tmp/project"),List.of());var now=Instant.now();UUID project=UUID.randomUUID(),id=UUID.randomUUID();
  var d=new EffectiveAgentDefinition("FORGE","a","a","instructions",new AgentModelSelection("provider","model",null),"fingerprint",Map.of(),false);
  var s=new StandaloneAgentSession(id,project,d,List.of(),StandaloneSessionCommands.workspaceFingerprint(space),"original-root","verified-version","RECOVERY_REQUIRED",3,UUID.randomUUID(),null,now,now,now);
  var c=new StandaloneExecutionClaim(s,s.activeTurnId(),"task","original-turn","recovery-owner",2,now.plusSeconds(20),false,true);
  when(spaces.resolve(project,null,List.of())).thenReturn(space);
  when(inspector.inspect(any())).thenReturn(new ProviderTurnRecoveryResult(ProviderTurnRecoveryState.TERMINAL,ProviderTurnRecoveryTerminalOutcome.SUCCEEDED,"verified","persisted reply"));
  var recovery=new StandaloneSessionRecovery(repo,inspector,spaces,definitions,mcp);recovery.recover(c);
  verify(inspector).inspect(argThat(i->i.providerConversationId().equals("original-root")&&i.providerTurnId().equals("original-turn")&&i.recoverReply()));
  verify(repo).reconcileRecovery(c,"SUCCEEDED","persisted reply",null);verify(gateway).revokeExecution(c.turnId());
  when(inspector.inspect(any())).thenReturn(ProviderTurnRecoveryResult.unknown("ambiguous"));recovery.recover(c);
  verify(repo).reconcileRecovery(c,null,null,"SESSION_RECOVERY_REQUIRED");
 }
}
