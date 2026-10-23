package com.sitionix.forgeagent.application.standalone;
import com.sitionix.forgeagent.application.definition.AgentDefinitionResolver;
import com.sitionix.forgeagent.application.runtime.*;
import com.sitionix.forgeagent.application.mcp.McpExecutionSelectionService;
import com.sitionix.forgeagent.domain.model.standalone.StandaloneExecutionClaim;
import com.sitionix.forgeagent.domain.model.ProviderTurnRecoveryState;
import com.sitionix.forgeagent.domain.port.standalone.StandaloneSessionRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
@Service
public class StandaloneSessionRecovery {
 private final StandaloneSessionRepository sessions;private final AgentExecutionRecoveryInspector inspector;
 private final ExecutionWorkspaceResolver workspaces;private final AgentDefinitionResolver definitions;private final McpExecutionSelectionService mcp;
 public StandaloneSessionRecovery(StandaloneSessionRepository sessions,AgentExecutionRecoveryInspector inspector,ExecutionWorkspaceResolver workspaces,AgentDefinitionResolver definitions,McpExecutionSelectionService mcp){this.sessions=sessions;this.inspector=inspector;this.workspaces=workspaces;this.definitions=definitions;this.mcp=mcp;}
 public void recover(StandaloneExecutionClaim claim) {
  String outcome=null,text=null,code="SESSION_RECOVERY_REQUIRED";
  try {
   mcp.revokeExecution(claim.turnId());var s=claim.session();
   var workspace=workspaces.resolve(s.projectId(),null,s.repositoryIds());
   if(!s.workspaceFingerprint().equals(StandaloneSessionCommands.workspaceFingerprint(workspace)))throw new IllegalStateException("Workspace changed");
   definitions.revalidate(s.definition(),workspace);
   // Undispatched turns are safe to terminate, but are never automatically replayed.
   if(!claim.dispatchStarted()) { outcome=claim.cancelRequested()?"CANCELLED":"FAILED";code="SESSION_EXECUTION_INTERRUPTED"; }
   else if(s.providerConversationId()!=null&&claim.providerTurnId()!=null) {
    var result=inspector.inspect(new AgentExecutionRecoveryInspection(s.definition().model().providerId(),s.providerVersion(),s.providerConversationId(),claim.providerTurnId(),workspace,Instant.now().plusSeconds(20),true));
    if(result.state()==ProviderTurnRecoveryState.TERMINAL){
     outcome=switch(result.terminalOutcome()){case SUCCEEDED->result.assistantText()==null?null:"SUCCEEDED";case FAILED->"FAILED";case CANCELLED->"CANCELLED";default->null;};
     text=result.assistantText();if(outcome!=null)code=null;
    }
   }
  }catch(RuntimeException incompatible){code="SESSION_RECOVERY_REQUIRED";}
  sessions.reconcileRecovery(claim,outcome,text,code);
 }
}
