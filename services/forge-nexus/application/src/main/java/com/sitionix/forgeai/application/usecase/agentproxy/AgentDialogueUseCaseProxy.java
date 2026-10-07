package com.sitionix.forgeai.application.usecase.agentproxy;

import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.AgentDialogueUseCases;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentDialogueUseCaseProxy implements AgentDialogueUseCases {
  private final ForgeAgentClient client;

  @Override
  public com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue.State getDialogue(
      UUID runId, UUID nodeRunId) {
    return client.getDialogue(runId, nodeRunId);
  }

  @Override
  public com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue.MessagePage
      listDialogueMessages(UUID runId, UUID nodeRunId, long afterSequence, int limit) {
    return client.listDialogueMessages(runId, nodeRunId, afterSequence, limit);
  }

  @Override
  public com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue.CommandReceipt
      sendDialogueMessage(
          UUID runId,
          UUID nodeRunId,
          com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue.SendRequest request) {
    return client.sendDialogueMessage(runId, nodeRunId, request);
  }

  @Override
  public com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue.CommandReceipt
      summarizeDialogue(
          UUID runId,
          UUID nodeRunId,
          com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue.SummaryRequest request) {
    return client.summarizeDialogue(runId, nodeRunId, request);
  }

  @Override
  public com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue.State completeDialogue(
      UUID runId,
      UUID nodeRunId,
      com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue.CompleteRequest request) {
    return client.completeDialogue(runId, nodeRunId, request);
  }
}
