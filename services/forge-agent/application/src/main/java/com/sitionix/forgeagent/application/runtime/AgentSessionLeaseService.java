package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.AgentSessionExecutionClaim;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import java.util.Optional;
import java.util.UUID;
import com.sitionix.forgeagent.domain.model.AgentExecutionTurnStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentSessionLeaseService {
    public static final int LEASE_SECONDS = 30;
    public static final int HEARTBEAT_SECONDS = 10;
    private final AgentExecutionSessionRepository repository;

    public Optional<AgentSessionExecutionClaim> claim(final UUID nodeRunId, final String ownerId) {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId must not be blank");
        return this.repository.acquire(nodeRunId, ownerId);
    }

    public int recoverExpired(final String ownerId) { return this.repository.recoverExpired(ownerId); }

    public void renew(final AgentSessionExecutionClaim claim) {
        if (!this.repository.renew(claim.sessionId(), claim.leaseOwnerId(), claim.leaseToken())) stale();
    }

    public void persistConversation(final AgentSessionExecutionClaim claim, final String conversationId, final String providerVersion) {
        if (conversationId == null || conversationId.isBlank()) throw new ConflictException("AGENT_CONTEXT_IDENTITY_MISMATCH", "Provider conversation identity was missing.");
        if (!this.repository.persistProviderConversation(claim.sessionId(), claim.leaseOwnerId(), claim.leaseToken(), conversationId, providerVersion)) stale();
    }

    public void persistTurn(final AgentSessionExecutionClaim claim, final String turnId) {
        if (turnId == null || turnId.isBlank()) throw new ConflictException("AGENT_CONTEXT_IDENTITY_MISMATCH", "Provider turn identity was missing.");
        if (!this.repository.persistProviderTurn(claim.sessionId(), claim.turnId(), claim.leaseOwnerId(), claim.leaseToken(), turnId)) stale();
    }

    public void lockCurrent(final AgentSessionExecutionClaim claim) {
        if (!this.repository.lockCurrentLease(claim.sessionId(), claim.leaseOwnerId(), claim.leaseToken())) stale();
    }

    public void finish(final AgentSessionExecutionClaim claim, final AgentExecutionTurnStatus status,
                       final String failureCode, final String failureMessage, final boolean sessionCorrupting) {
        if (!this.repository.finish(claim.sessionId(), claim.turnId(), claim.leaseOwnerId(), claim.leaseToken(),
                status, failureCode, failureMessage, sessionCorrupting)) stale();
    }

    private static void stale() { throw new ConflictException("STALE_AGENT_SESSION_LEASE", "Agent context ownership was lost."); }
}
