package com.sitionix.forgeagent.application.standalone;

import com.sitionix.forgeagent.application.definition.AgentDefinitionResolver;
import com.sitionix.forgeagent.application.mcp.McpExecutionSelectionService;
import com.sitionix.forgeagent.application.runtime.*;
import com.sitionix.forgeagent.domain.exception.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.model.standalone.*;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.standalone.StandaloneSessionRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Owns standalone turns, never workflow routing. All inference uses the shared execution port. */
public final class StandaloneTurnWorker implements AutoCloseable {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(StandaloneTurnWorker.class);
    private final StandaloneSessionRepository sessions;private final ProjectRepository projects;
    private final ExecutionWorkspaceResolver workspaces;private final AgentDefinitionResolver definitions;
    private final AgentTurnExecutionPort execution;private final McpExecutionSelectionService mcp;
    private final ScheduledExecutorService heartbeat;private final ExecutorService pool;
    private final Semaphore slots;private final String owner=UUID.randomUUID().toString();private final int timeout;
    private final StandaloneSessionRecovery recovery;
    private final Set<UUID> active=ConcurrentHashMap.newKeySet();
    private final Map<UUID,StandaloneExecutionClaim> pendingCleanup=new ConcurrentHashMap<>();
    public StandaloneTurnWorker(StandaloneSessionRepository sessions,ProjectRepository projects,ExecutionWorkspaceResolver workspaces,
            AgentDefinitionResolver definitions,AgentTurnExecutionPort execution,McpExecutionSelectionService mcp,
            ScheduledExecutorService heartbeat,int concurrency,int timeoutSeconds,StandaloneSessionRecovery recovery) {
        if(concurrency<1||concurrency>8||timeoutSeconds<30||timeoutSeconds>1800)throw new IllegalArgumentException("Invalid standalone execution limits");
        this.sessions=sessions;this.projects=projects;this.workspaces=workspaces;this.definitions=definitions;this.execution=execution;this.mcp=mcp;this.heartbeat=heartbeat;
        this.recovery=recovery;this.slots=new Semaphore(concurrency);this.timeout=timeoutSeconds;this.pool=Executors.newVirtualThreadPerTaskExecutor();
    }
    public void poll() {
        for(var entry:pendingCleanup.entrySet())try{UUID id=entry.getKey();execution.interrupt(id);sessions.runtimeCleaned(entry.getValue());if(pendingCleanup.remove(id,entry.getValue())){active.remove(id);slots.release();}}catch(RuntimeException stillOwned){log.warn("Standalone runtime cleanup remains unconfirmed turnId={}",entry.getKey());}
        for(UUID turn:sessions.cancellationRequests(100))if(active.contains(turn))execution.interrupt(turn);
        for(var expired:sessions.expired(100)) {
            // Never replay a dispatched or uncertain turn. A separate provider inspection may prove its outcome.
            if(active.contains(expired.turnId()))execution.interrupt(expired.turnId());
            sessions.quarantine(expired,"SESSION_RECOVERY_REQUIRED");mcp.revokeExecution(expired.turnId());
        }
        if(slots.tryAcquire()) {
            final Optional<StandaloneExecutionClaim> claim;
            try{claim=sessions.claimRecovery(owner);}catch(RuntimeException failure){slots.release();throw failure;}
            if(claim.isEmpty())slots.release();
            else pool.submit(()->{try{recovery.recover(claim.get());}finally{slots.release();}});
        }
        while(slots.tryAcquire()) {
            final Optional<StandaloneExecutionClaim> next;
            try{next=sessions.claim(owner,timeout);}catch(RuntimeException failure){slots.release();throw failure;}
            if(next.isEmpty()){slots.release();break;}
            var claim=next.get();active.add(claim.turnId());
            try{pool.submit(()->run(claim));}catch(RuntimeException rejected){active.remove(claim.turnId());slots.release();throw rejected;}
        }
    }
    private void require(boolean result) { if(!result)throw new ConflictException("STALE_SESSION_LEASE","Session execution ownership was lost."); }
    private void run(StandaloneExecutionClaim claim) {
        var dispatch=new java.util.concurrent.atomic.AtomicBoolean();
        var cleaned=new java.util.concurrent.atomic.AtomicBoolean(true);
        try(var beat=new AgentSessionHeartbeat(()->require(sessions.renew(claim)),heartbeat,()->execution.interrupt(claim.turnId()))) {
            var s=claim.session();if(projects.findById(s.projectId()).isEmpty())throw new NotFoundException("PROJECT_NOT_FOUND","Project was not found.");
            var workspace=workspaces.resolve(s.projectId(),null,s.repositoryIds());
            if(!s.workspaceFingerprint().equals(StandaloneSessionCommands.workspaceFingerprint(workspace)))throw new ConflictException("SESSION_WORKSPACE_CHANGED","Session workspace identity changed.");
            definitions.revalidate(s.definition(),workspace);
            var prepared=mcp.prepareStandalone(claim,claim.deadline());
            var callbacks=new AgentTurnIdentityCallbacks() {
                public void executionStarted(Runnable cancellation){cleaned.set(false);}
                public void executionFinished(boolean confirmed){cleaned.set(confirmed);}
                public void conversationStarted(String id,String version){require(sessions.persistConversation(claim,id,version));}
                public void turnStarted(String id){require(sessions.persistTurn(claim,id));}
                public void dispatchTurnStart(Runnable write){sessions.dispatch(claim,()->{mcp.activateStandalone(claim);dispatch.set(true);write.run();});}
            };
            var sink=new AgentTurnEventSink() {
                public void executionEvent(AgentExecutionEventCandidate e){require(sessions.appendEvent(claim,e));}
                public void eventCaptureCompleted(AgentExecutionEventCandidate e){require(sessions.appendEvent(claim,e));}
                public void eventCaptureDegraded(RuntimeException failure){
                    sessions.appendEvent(claim,new AgentExecutionEventCandidate(AgentExecutionEventType.WARNING,null,"activity",null,"{\"code\":\"ACTIVITY_CAPTURE_DEGRADED\"}",Instant.now()));
                }
            };
            require(sessions.runtimeStarted(claim));cleaned.set(false);
            var result=execution.execute(new AgentTurnExecutionRequest(claim.turnId(),s.definition(),claim.input(),null,workspace,AgentTurnExecutionRequest.Mode.DURABLE,
                    s.providerConversationId(),s.providerVersion(),false,prepared.hasContext()?prepared.selection():null,prepared.launchGrants(),callbacks,new com.sitionix.forgeagent.domain.model.execution.AgentExecutionOwner.Standalone(s.projectId(),s.id(),claim.turnId())),sink);
            beat.verifyOwnership();require(sessions.current(claim));
            if(result.text()==null||result.text().isBlank())throw new ConflictException("EMPTY_AGENT_REPLY","Agent returned no reply.");
            if(!cleaned.get())throw new ConflictException("SESSION_RUNTIME_CLEANUP_REQUIRED","Runtime cleanup must be confirmed before continuing.");
            require(sessions.runtimeCleaned(claim));
            require(sessions.finish(claim,"SUCCEEDED",result.text(),null,false));
        }catch(RuntimeException failure) {
            String code=failure instanceof ForgeAgentException e?e.code():"AGENT_SESSION_EXECUTION_FAILED";
            // A known final provider failure and process cleanup can be refined by provider recovery.
            if(cleaned.get())sessions.runtimeCleaned(claim);
            sessions.finish(claim,"FAILED",null,code,dispatch.get()||!cleaned.get());
            log.warn("Standalone execution failed sessionId={} turnId={} code={} failureClass={}",claim.session().id(),claim.turnId(),code,failure.getClass().getSimpleName());
        }finally{mcp.revokeExecution(claim.turnId());if(cleaned.get()){active.remove(claim.turnId());slots.release();}else pendingCleanup.put(claim.turnId(),claim);}
    }
    @Override public void close(){for(UUID id:active)execution.interrupt(id);pool.close();}
}
