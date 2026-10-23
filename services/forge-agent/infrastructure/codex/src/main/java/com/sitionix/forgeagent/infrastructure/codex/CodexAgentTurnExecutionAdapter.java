package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.*;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventCandidate;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** The shared invocation boundary. Process ownership, RPC and authorization remain in CodexClient. */
@Component
public final class CodexAgentTurnExecutionAdapter implements AgentTurnExecutionPort {
    private final CodexClient client;
    private final ObjectMapper json;
    private final ConcurrentHashMap<UUID, Cancellation> active = new ConcurrentHashMap<>();
    public CodexAgentTurnExecutionAdapter(CodexClient client,ObjectMapper json) { this.client=client;this.json=json; }

    @Override public AgentTurnResult execute(AgentTurnExecutionRequest r,AgentTurnEventSink events) {
        if (!"codex".equals(r.definition().model().providerId())) throw new IllegalArgumentException("Unsupported execution provider");
        if (r.definition().nativeSubagents() && !(r.owner() instanceof com.sitionix.forgeagent.domain.model.execution.AgentExecutionOwner.Standalone)) throw new IllegalArgumentException("Native subagents require standalone ownership");
        final com.fasterxml.jackson.databind.JsonNode schema;
        try { schema=r.outputSchemaJson()==null?null:json.readTree(r.outputSchemaJson()); }
        catch (java.io.IOException bad) { throw new IllegalArgumentException("Invalid output contract",bad); }
        var request=new CodexTurnRequest(r.input(),r.definition().instructions(),r.definition().model().modelId(),
                r.definition().model().effortId(),schema,r.workspace(),r.sharedSessionGroup(),r.mcpSelection(),r.definition().nativeSubagents(),r.owner() instanceof com.sitionix.forgeagent.domain.model.execution.AgentExecutionOwner.Standalone?r.definition().resources():null);
        Cancellation cancellation=new Cancellation();
        if(active.putIfAbsent(r.executionId(),cancellation)!=null) throw new IllegalStateException("Execution already active");
        var callbacks=new CodexExecutionIdentityCallbacks() {
            public void executionStarted(Runnable cancel) { cancellation.register(cancel);r.identities().executionStarted(cancel); }
            public void conversationStarted(String id,String version) { r.identities().conversationStarted(id,version); }
            public void turnStarted(String id) { r.identities().turnStarted(id); }
            public void dispatchTurnStart(Runnable write) { r.identities().dispatchTurnStart(write); }
            public void executionEvent(AgentExecutionEventCandidate e) { events.executionEvent(e); }
            public void eventCaptureCompleted(AgentExecutionEventCandidate e) { events.eventCaptureCompleted(e); }
            public void eventCaptureDegraded(RuntimeException e) { events.eventCaptureDegraded(e); }
        };
        boolean succeeded=false;
        try {
            String result=switch(r.mode()) {
                case FRESH -> r.grants()==null?client.execute(request):client.execute(request,r.grants());
                case TRACKED_FRESH -> r.grants()==null?client.executeTrackedFresh(request,callbacks):client.executeTrackedFresh(request,r.grants(),callbacks);
                case DURABLE -> r.grants()==null?client.executeDurable(request,r.conversationId(),r.providerVersion(),callbacks):client.executeDurable(request,r.conversationId(),r.providerVersion(),r.grants(),callbacks);
            };
            succeeded=true;return new AgentTurnResult(result);
        } finally {
            // Workflow retains its existing secured cancellation lifecycle in CodexAgentExecutor.
            boolean confirmed=cancellation.finished(succeeded || !(r.owner() instanceof com.sitionix.forgeagent.domain.model.execution.AgentExecutionOwner.Standalone));
            r.identities().executionFinished(confirmed);
            if(confirmed)active.remove(r.executionId(),cancellation);
        }
    }
    @Override public void interrupt(UUID id) { var cancellation=active.get(id);if(cancellation!=null){cancellation.cancel();if(cancellation.finished)active.remove(id,cancellation);} }
    private static final class Cancellation {
        private Runnable cancel;private boolean requested,finished,executionEnded,confirmed;
        synchronized void register(Runnable action) { cancel=action;if(requested)action.run(); }
        synchronized void cancel() { requested=true;if(cancel!=null&&!finished){cancel.run();confirmed=true;if(executionEnded)finished=true;} }
        synchronized boolean finished(boolean succeeded) {
            executionEnded=true;
            if(!succeeded&&!confirmed&&cancel!=null)try{cancel.run();confirmed=true;}catch(RuntimeException unconfirmed){return false;}
            finished=true;return true;
        }
    }
}
