package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.application.runtime.AgentContextForkProvider;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForkAgentExecutionContextUseCaseTest {
    final AgentExecutionSessionRepository sessions = mock(AgentExecutionSessionRepository.class);
    final AgentContextForkProvider provider = mock(AgentContextForkProvider.class);
    final AgentExecutionContextUseCases contexts = mock(AgentExecutionContextUseCases.class);
    final ForkAgentExecutionContextUseCase useCase = new ForkAgentExecutionContextUseCase(sessions, provider, contexts);
    final AgentExecutionSession session = new AgentExecutionSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
            "codex","parent","0.154.0",NodeContextMode.REUSE_WITHIN_WORKFLOW_NODE,AgentExecutionSessionStatus.FORKING,null,null,null,2,
            null,null,null,Instant.now(),Instant.now(),null,null);
    final AgentExecutionTurn turn = new AgentExecutionTurn(UUID.randomUUID(), session.id(),UUID.randomUUID(),"exact-latest",3,
            AgentExecutionTurnStatus.SUCCEEDED,null,null,null,null,null,null,null,Instant.now(),Instant.now());
    final AgentContextForkPreparation prepared = new AgentContextForkPreparation(session, turn);

    void ready() {
        when(sessions.findSession(session.id())).thenReturn(Optional.of(session));
        when(sessions.prepareFork(session.id())).thenReturn(prepared);
    }
    @Test void forksExactPersistedBoundaryOnceAndReturnsAuthoritativeContext() {
        ready();
        when(provider.fork("codex","0.154.0","parent","exact-latest")).thenReturn("child-exact");
        var truth = List.of(new AgentExecutionContext(session, turn, false));
        when(contexts.list(session.workflowRunId())).thenReturn(truth);
        assertThat(useCase.execute(session.id())).isSameAs(truth);
        var order = inOrder(provider,sessions,contexts);
        order.verify(sessions).findSession(session.id());
        order.verify(provider).validateSupport("codex","0.154.0");
        order.verify(sessions).prepareFork(session.id());
        order.verify(provider).fork("codex","0.154.0","parent","exact-latest");
        order.verify(sessions).completeFork(prepared,"child-exact");
        order.verify(contexts).list(session.workflowRunId());
        verify(sessions, never()).allocate(any(),any());
    }
    @Test void unsupportedIsRejectedBeforePrepare() {
        ready();
        doThrow(new ConflictException(AgentContextForkEligibility.UNSUPPORTED,"Unsupported")).when(provider).validateSupport(any(),any());
        assertThatThrownBy(() -> useCase.execute(session.id())).isInstanceOf(ConflictException.class);
        verify(sessions,never()).prepareFork(any());
        verify(provider,never()).fork(any(),any(),any(),any());
    }
    @Test void allocationOrResetWinningRejectsWithoutProviderSideEffect() {
        ready();
        when(sessions.prepareFork(session.id())).thenThrow(new ConflictException(AgentContextForkEligibility.BUSY,"Pending"));
        assertThatThrownBy(() -> useCase.execute(session.id())).extracting("code").isEqualTo(AgentContextForkEligibility.BUSY);
        verify(provider,never()).fork(any(),any(),any(),any());
        verify(sessions,never()).abortFork(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {" ", "parent"})
    void invalidProviderChildNeverInstallsSuccessor(final String child) {
        ready();
        when(provider.fork(any(),any(),any(),any())).thenReturn(child);
        assertThatThrownBy(() -> useCase.execute(session.id())).extracting("code").isEqualTo(AgentContextForkEligibility.FAILED);
        verify(sessions).abortFork(prepared);
        verify(sessions,never()).completeFork(any(),any());
    }
    @Test void failedOrMalformedForkRestoresSourceWithoutRetryOrChild() {
        ready();
        when(provider.fork(any(),any(),any(),any())).thenThrow(new RuntimeException("timeout"));
        assertThatThrownBy(() -> useCase.execute(session.id())).isInstanceOf(ConflictException.class);
        verify(sessions).abortFork(prepared);
        verify(provider,times(1)).fork(any(),any(),any(),any());
        verify(sessions,never()).completeFork(any(),any());
    }
    @Test void finalizationConflictDoesNotRetryOrOverwriteNewTruth() {
        ready();
        when(provider.fork(any(),any(),any(),any())).thenReturn("child");
        when(sessions.completeFork(prepared,"child")).thenThrow(new ConflictException(AgentContextForkEligibility.CONFLICT,"Stopped"));
        assertThatThrownBy(() -> useCase.execute(session.id())).isInstanceOf(ConflictException.class);
        verify(provider,times(1)).fork(any(),any(),any(),any());
        verify(sessions,never()).abortFork(any());
    }
}
