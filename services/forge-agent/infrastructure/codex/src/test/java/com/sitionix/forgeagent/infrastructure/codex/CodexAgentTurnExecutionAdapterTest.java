package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.*;
import com.sitionix.forgeagent.domain.model.AgentModelSelection;
import com.sitionix.forgeagent.domain.model.execution.EffectiveAgentDefinition;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodexAgentTurnExecutionAdapterTest {
    @Test void plainTextDurableTurnUsesSelectedRootAndResumesExactlySavedIdentity() {
        CodexClient client=mock(CodexClient.class);
        when(client.executeDurable(any(),eq("saved-root"),eq("0.163.0"),any(CodexExecutionIdentityCallbacks.class))).thenAnswer(inv -> {
            CodexTurnRequest turn=inv.getArgument(0);
            assertThat(turn.userInput()).isEqualTo("ordinary follow-up");
            assertThat(turn.developerInstructions()).isEqualTo("Selected agent instructions");
            assertThat(turn.modelId()).isEqualTo("agent-model");
            assertThat(turn.outputSchema()).isNull();
            return "ordinary assistant answer";
        });
        var adapter=new CodexAgentTurnExecutionAdapter(client,new ObjectMapper());
        var definition=new EffectiveAgentDefinition("FORGE","agent","Agent","Selected agent instructions",new AgentModelSelection("codex","agent-model","high"),"fingerprint",Map.of(),false);
        var request=new AgentTurnExecutionRequest(UUID.randomUUID(),definition,"ordinary follow-up",null,new ExecutionWorkspace(Path.of("/tmp/project"),List.of(Path.of("/tmp/project"))),AgentTurnExecutionRequest.Mode.DURABLE,"saved-root","0.163.0",false,null,null,AgentTurnIdentityCallbacks.noop());
        assertThat(adapter.execute(request,AgentTurnEventSink.noop()).text()).isEqualTo("ordinary assistant answer");
        verify(client,never()).execute(any());
        verify(client,times(1)).executeDurable(any(),eq("saved-root"),eq("0.163.0"),any(CodexExecutionIdentityCallbacks.class));
    }
    @Test void standaloneRetainsCleanupHandleUntilOwnedTerminationIsConfirmed() {
        var client=mock(CodexClient.class);var attempts=new java.util.concurrent.atomic.AtomicInteger();
        when(client.executeDurable(any(),isNull(),isNull(),any(CodexExecutionIdentityCallbacks.class))).thenAnswer(call->{
            CodexExecutionIdentityCallbacks callbacks=call.getArgument(3);callbacks.executionStarted(()->{if(attempts.incrementAndGet()<3)throw new IllegalStateException("cleanup unconfirmed");});
            throw new IllegalStateException("provider failed before dispatch");
        });
        var adapter=new CodexAgentTurnExecutionAdapter(client,new ObjectMapper());UUID id=UUID.randomUUID();
        var d=new EffectiveAgentDefinition("FORGE","a","a","i",new AgentModelSelection("codex","m",null),"f",Map.of(),false);
        var confirmed=new java.util.concurrent.atomic.AtomicReference<Boolean>();
        var callbacks=new AgentTurnIdentityCallbacks(){public void executionFinished(boolean value){confirmed.set(value);}};
        var request=new AgentTurnExecutionRequest(id,d,"hi",null,new ExecutionWorkspace(Path.of("/tmp/project"),List.of()),AgentTurnExecutionRequest.Mode.DURABLE,null,null,false,null,null,callbacks,new com.sitionix.forgeagent.domain.model.execution.AgentExecutionOwner.Standalone(UUID.randomUUID(),UUID.randomUUID(),id));
        assertThatThrownBy(()->adapter.execute(request,AgentTurnEventSink.noop())).hasMessage("provider failed before dispatch");
        assertThat(confirmed.get()).isFalse();assertThatThrownBy(()->adapter.interrupt(id)).hasMessage("cleanup unconfirmed");
        adapter.interrupt(id);adapter.interrupt(id);assertThat(attempts).hasValue(3);
    }
    @Test void unsupportedProviderIsRejectedBeforeStartingProcess() {
        CodexClient client=mock(CodexClient.class);
        var d=new EffectiveAgentDefinition("FORGE","a","a","i",new AgentModelSelection("unknown","m",null),"f",Map.of(),false);
        var r=new AgentTurnExecutionRequest(UUID.randomUUID(),d,"hi",null,new ExecutionWorkspace(Path.of("/tmp"),List.of()),AgentTurnExecutionRequest.Mode.DURABLE,null,null,false,null,null,AgentTurnIdentityCallbacks.noop());
        assertThatThrownBy(()->new CodexAgentTurnExecutionAdapter(client,new ObjectMapper()).execute(r,AgentTurnEventSink.noop())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }
}
