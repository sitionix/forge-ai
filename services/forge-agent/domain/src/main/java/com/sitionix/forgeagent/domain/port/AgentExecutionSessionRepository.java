package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.AgentExecutionAllocation;
import com.sitionix.forgeagent.domain.model.AgentExecutionSession;
import com.sitionix.forgeagent.domain.model.AgentExecutionRecoveryClaim;
import com.sitionix.forgeagent.domain.model.AgentExecutionRecoveryReconciliation;
import com.sitionix.forgeagent.domain.model.AgentSessionExecutionClaim;
import com.sitionix.forgeagent.domain.model.AgentExecutionTurnStatus;
import com.sitionix.forgeagent.domain.model.NodeRun;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface AgentExecutionSessionRepository {
    List<com.sitionix.forgeagent.domain.model.AgentExecutionContext> findContextsByWorkflowRunId(UUID workflowRunId);
    com.sitionix.forgeagent.domain.model.AgentContextForkPreparation prepareFork(UUID sessionId);
    UUID completeFork(com.sitionix.forgeagent.domain.model.AgentContextForkPreparation preparation, String providerConversationId);
    void abortFork(com.sitionix.forgeagent.domain.model.AgentContextForkPreparation preparation);
    int reconcileStaleForks();
    Optional<AgentExecutionSession> findSession(UUID sessionId);
    void lockReusableScope(UUID workflowRunId, UUID sourceNodeId, UUID repositoryId);
    void lockSharedScope(UUID workflowRunId, UUID contextIterationId, String contextGroupKey, UUID repositoryId);
    Optional<AgentExecutionSession> lockSession(UUID sessionId);
    boolean hasPendingTurns(UUID sessionId);
    void markContextReset(UUID sessionId);
    AgentExecutionAllocation allocate(NodeRun nodeRun, String providerId);
    Optional<AgentExecutionAllocation> findByNodeRunId(UUID nodeRunId);
    List<AgentExecutionAllocation> findByWorkflowRunId(UUID workflowRunId);
    Optional<AgentSessionExecutionClaim> acquire(UUID nodeRunId, String ownerId);
    boolean renew(UUID sessionId, String ownerId, long token);
    boolean persistProviderConversation(UUID sessionId, String ownerId, long token, String conversationId, String providerVersion);
    boolean persistProviderTurn(UUID sessionId, UUID turnId, String ownerId, long token, String providerTurnId);
    boolean lockCurrentLease(UUID sessionId, String ownerId, long token);
    boolean finish(UUID sessionId, UUID turnId, String ownerId, long token,
                   AgentExecutionTurnStatus turnStatus, String failureCode, String failureMessage,
                   boolean sessionCorrupting);
    Optional<AgentExecutionRecoveryClaim> claimExpiredRecovery(String ownerId);
    boolean reconcileRecovery(AgentExecutionRecoveryClaim claim, AgentExecutionRecoveryReconciliation reconciliation);
    boolean cancel(UUID nodeRunId);
}
