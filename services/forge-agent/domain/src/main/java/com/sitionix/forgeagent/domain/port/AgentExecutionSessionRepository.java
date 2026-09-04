package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.AgentExecutionAllocation;
import com.sitionix.forgeagent.domain.model.AgentSessionExecutionClaim;
import com.sitionix.forgeagent.domain.model.AgentExecutionTurnStatus;
import com.sitionix.forgeagent.domain.model.NodeRun;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface AgentExecutionSessionRepository {
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
    int recoverExpired(String ownerId);
    boolean cancel(UUID nodeRunId);
}
