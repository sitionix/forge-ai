package com.sitionix.forgeai.infrastructure.agentclient;

import com.sitionix.forgeai.domain.model.agentproxy.AgentDialogue;
import com.sitionix.forgeai.domain.model.agentproxy.AgentNodeRunOutputDocument;
import com.sitionix.forgeai.infrastructure.agentclient.dto.DialogueClientDtos;

public final class DialogueClientMapper {
  private DialogueClientMapper() {}

  public static AgentDialogue.SendRequest map(DialogueClientDtos.SendRequest v) {
    if (v == null) return null;
    return new AgentDialogue.SendRequest(v.requestId(), v.expectedRevision(), v.text());
  }

  public static AgentDialogue.SummaryRequest map(DialogueClientDtos.SummaryRequest v) {
    if (v == null) return null;
    return new AgentDialogue.SummaryRequest(v.requestId(), v.expectedRevision());
  }

  public static AgentDialogue.CompleteRequest map(DialogueClientDtos.CompleteRequest v) {
    if (v == null) return null;
    return new AgentDialogue.CompleteRequest(
        v.requestId(), v.expectedRevision(), v.summaryRevisionId(), v.outputPortId());
  }

  public static AgentDialogue.Question map(DialogueClientDtos.Question v) {
    if (v == null) return null;
    return new AgentDialogue.Question(
        v.id(), v.text(), v.reason(), v.blocking(), v.recommendation());
  }

  public static AgentDialogue.Decision map(DialogueClientDtos.Decision v) {
    if (v == null) return null;
    return new AgentDialogue.Decision(v.id(), v.text(), v.userMessageIds());
  }

  public static AgentDialogue.Source map(DialogueClientDtos.Source v) {
    if (v == null) return null;
    return new AgentDialogue.Source(v.title(), v.uri(), v.revision());
  }

  public static AgentDialogue.Reply map(DialogueClientDtos.Reply v) {
    if (v == null) return null;
    return new AgentDialogue.Reply(
        v.message(),
        v.draft() == null || v.draft().isNull()
            ? null
            : new AgentNodeRunOutputDocument(v.draft().toString()),
        v.questions() == null
            ? null
            : v.questions().stream().map(DialogueClientMapper::map).toList(),
        v.decisions() == null
            ? null
            : v.decisions().stream().map(DialogueClientMapper::map).toList(),
        v.sources() == null ? null : v.sources().stream().map(DialogueClientMapper::map).toList(),
        v.readyForReview());
  }

  public static AgentDialogue.Revision map(DialogueClientDtos.Revision v) {
    if (v == null) return null;
    return new AgentDialogue.Revision(
        v.id(),
        v.revision(),
        v.turnId(),
        v.kind(),
        v.inputRevision(),
        map(v.result()),
        v.createdAt());
  }

  public static AgentDialogue.Turn map(DialogueClientDtos.Turn v) {
    if (v == null) return null;
    return new AgentDialogue.Turn(
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

  public static AgentDialogue.Completion map(DialogueClientDtos.Completion v) {
    if (v == null) return null;
    return new AgentDialogue.Completion(
        v.requestId(),
        v.summaryRevisionId(),
        v.outputPortId(),
        v.disposition(),
        v.revision(),
        v.completedAt());
  }

  public static AgentDialogue.Message map(DialogueClientDtos.Message v) {
    if (v == null) return null;
    return new AgentDialogue.Message(
        v.id(), v.sequence(), v.role(), v.text(), v.turnId(), v.createdAt());
  }

  public static AgentDialogue.MessagePage map(DialogueClientDtos.MessagePage v) {
    if (v == null) return null;
    return new AgentDialogue.MessagePage(
        v.messages() == null ? null : v.messages().stream().map(DialogueClientMapper::map).toList(),
        v.nextSequence(),
        v.hasMore());
  }

  public static AgentDialogue.State map(DialogueClientDtos.State v) {
    if (v == null) return null;
    return new AgentDialogue.State(
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
        v.createdAt(),
        v.updatedAt());
  }
}
