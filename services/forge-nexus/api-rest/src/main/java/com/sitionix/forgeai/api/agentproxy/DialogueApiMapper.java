package com.sitionix.forgeai.api.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunOutputDocument;

public final class DialogueApiMapper {
  private DialogueApiMapper() {}

  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();

  public static AgentDialogueDtos.SendRequest map(AgentDialogue.SendRequest v) {
    if (v == null) return null;
    return new AgentDialogueDtos.SendRequest(v.requestId(), v.expectedRevision(), v.text());
  }

  public static AgentDialogueDtos.SummaryRequest map(AgentDialogue.SummaryRequest v) {
    if (v == null) return null;
    return new AgentDialogueDtos.SummaryRequest(v.requestId(), v.expectedRevision());
  }

  public static AgentDialogueDtos.CompleteRequest map(AgentDialogue.CompleteRequest v) {
    if (v == null) return null;
    return new AgentDialogueDtos.CompleteRequest(
        v.requestId(), v.expectedRevision(), v.summaryRevisionId(), v.outputPortId());
  }

  public static AgentDialogueDtos.Question map(AgentDialogue.Question v) {
    if (v == null) return null;
    return new AgentDialogueDtos.Question(
        v.id(), v.text(), v.reason(), v.blocking(), v.recommendation());
  }

  public static AgentDialogueDtos.Decision map(AgentDialogue.Decision v) {
    if (v == null) return null;
    return new AgentDialogueDtos.Decision(v.id(), v.text(), v.userMessageIds());
  }

  public static AgentDialogueDtos.Source map(AgentDialogue.Source v) {
    if (v == null) return null;
    return new AgentDialogueDtos.Source(v.title(), v.uri(), v.revision());
  }

  public static AgentDialogueDtos.Reply map(AgentDialogue.Reply v) {
    if (v == null) return null;
    return new AgentDialogueDtos.Reply(
        v.message(),
        draft(v.draft()),
        v.questions() == null ? null : v.questions().stream().map(DialogueApiMapper::map).toList(),
        v.decisions() == null ? null : v.decisions().stream().map(DialogueApiMapper::map).toList(),
        v.sources() == null ? null : v.sources().stream().map(DialogueApiMapper::map).toList(),
        v.readyForReview());
  }

  public static AgentDialogueDtos.Revision map(AgentDialogue.Revision v) {
    if (v == null) return null;
    return new AgentDialogueDtos.Revision(
        v.id(),
        v.revision(),
        v.turnId(),
        v.kind(),
        v.inputRevision(),
        map(v.result()),
        v.createdAt());
  }

  public static AgentDialogueDtos.Turn map(AgentDialogue.Turn v) {
    if (v == null) return null;
    return new AgentDialogueDtos.Turn(
        v.id(),
        v.kind(),
        v.requestId(),
        v.triggeringMessageId(),
        v.inputRevision(),
        v.status(),
        v.executionTurnId(),
        v.failureCode(),
        v.failureMessage(),
        v.createdAt(),
        v.finishedAt());
  }

  public static AgentDialogueDtos.Completion map(AgentDialogue.Completion v) {
    if (v == null) return null;
    return new AgentDialogueDtos.Completion(
        v.requestId(),
        v.summaryRevisionId(),
        v.outputPortId(),
        v.disposition(),
        v.revision(),
        v.completedAt());
  }

  public static AgentDialogueDtos.Message map(AgentDialogue.Message v) {
    if (v == null) return null;
    return new AgentDialogueDtos.Message(
        v.id(), v.sequence(), v.role(), v.text(), v.turnId(), v.createdAt());
  }

  public static AgentDialogueDtos.MessagePage map(AgentDialogue.MessagePage v) {
    if (v == null) return null;
    return new AgentDialogueDtos.MessagePage(
        v.messages() == null ? null : v.messages().stream().map(DialogueApiMapper::map).toList(),
        v.nextSequence(),
        v.hasMore());
  }

  public static AgentDialogueDtos.State map(AgentDialogue.State v) {
    if (v == null) return null;
    return new AgentDialogueDtos.State(
        v.nodeRunId(),
        v.state(),
        v.revision(),
        v.summaryRevisionId(),
        map(v.latestRevision()),
        map(v.activeTurn()),
        map(v.completion()),
        map(v.messages()),
        v.lastMessageSequence(),
        v.turnCount(),
        v.maxTurns(),
        v.maxMessageCodePoints(),
        v.createdAt(),
        v.updatedAt());
  }

  private static com.fasterxml.jackson.databind.JsonNode draft(AgentNodeRunOutputDocument v) {
    if (v == null) return null;
    try {
      return JSON.readTree(v.jsonValue());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Invalid persisted dialogue draft", e);
    }
  }
}
