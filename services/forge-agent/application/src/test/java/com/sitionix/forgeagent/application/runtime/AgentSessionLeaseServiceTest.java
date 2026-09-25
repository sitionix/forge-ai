package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.AgentSessionExecutionClaim;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import com.sitionix.forgeagent.application.mcp.McpGatewayService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AgentSessionLeaseServiceTest {
    private final AgentExecutionSessionRepository repository=mock(AgentExecutionSessionRepository.class);
    private final AgentSessionLeaseService service=new AgentSessionLeaseService(repository);
    private final AgentSessionExecutionClaim claim=new AgentSessionExecutionClaim(UUID.randomUUID(),UUID.randomUUID(),
            UUID.randomUUID(),"worker-a",7, Instant.now().plusSeconds(30),"thread-1","codex");

    @Test
    void staleProviderResultCannotPassLeaseGuard() {
        when(repository.lockCurrentLease(claim.sessionId(),"worker-a",7)).thenReturn(false);
        assertThatThrownBy(() -> service.lockCurrent(claim)).isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).code()).isEqualTo("STALE_AGENT_SESSION_LEASE");
    }

    @Test
    void renewalKeepsTheSameFencingToken() {
        when(repository.renew(claim.sessionId(),"worker-a",7)).thenReturn(true);
        service.renew(claim);
        verify(repository).renew(claim.sessionId(),"worker-a",7);
        verifyNoMoreInteractions(repository);
    }

    @Test void completedTurnRevokesRuntimeGrantButFailedFinishDoesNot() {
        var gateway = mock(McpGatewayService.class);
        @SuppressWarnings("unchecked") ObjectProvider<McpGatewayService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        var withGateway = new AgentSessionLeaseService(repository, provider);
        when(repository.finish(eq(claim.sessionId()), eq(claim.turnId()), eq("worker-a"), eq(7L),
                any(), isNull(), isNull(), eq(false))).thenReturn(false, true);
        assertThatThrownBy(() -> withGateway.finish(claim,
                com.sitionix.forgeagent.domain.model.AgentExecutionTurnStatus.SUCCEEDED, null, null, false))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(gateway);
        withGateway.finish(claim, com.sitionix.forgeagent.domain.model.AgentExecutionTurnStatus.SUCCEEDED,
                null, null, false);
        verify(gateway).revokeExecution(claim.turnId());
    }
}
