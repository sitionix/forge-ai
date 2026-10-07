package com.sitionix.forgeai.domain.usecase;
import java.util.UUID;
public interface AgentDialogueUseCases {
    com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue.State getDialogue(UUID runId, UUID nodeRunId);
    com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue.MessagePage listDialogueMessages(UUID runId, UUID nodeRunId, long afterSequence, int limit);
    com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue.CommandReceipt sendDialogueMessage(UUID runId, UUID nodeRunId, com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue.SendRequest request);
    com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue.CommandReceipt summarizeDialogue(UUID runId, UUID nodeRunId, com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue.SummaryRequest request);
    com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue.State completeDialogue(UUID runId, UUID nodeRunId, com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue.CompleteRequest request);
}
