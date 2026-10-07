package com.sitionix.forgeai.api.agentproxy;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AgentDialogueDtos {
    private AgentDialogueDtos() { }
    public record SendRequest(@NotNull UUID requestId, @NotNull @Min(0) Long expectedRevision, @NotNull String text) { }
    public record SummaryRequest(@NotNull UUID requestId, @NotNull @Min(0) Long expectedRevision) { }
    public record CompleteRequest(@NotNull UUID requestId, @NotNull @Min(0) Long expectedRevision,
                                  @NotNull UUID summaryRevisionId, @NotNull UUID outputPortId) { }
    public record Question(String id, String text, String reason, boolean blocking, String recommendation) { }
    public record Decision(String id, String text, List<UUID> userMessageIds) { }
    public record Source(String title, String uri, String revision) { }
    public record Reply(String message, JsonNode draft, List<Question> questions, List<Decision> decisions,
                        List<Source> sources, boolean readyForReview) { }
    public record Revision(UUID id, long revision, UUID turnId, String kind, long inputRevision, Reply result, Instant createdAt) { }
    public record Turn(UUID id, String kind, UUID requestId, UUID triggeringMessageId, long inputRevision, String status,
                       UUID executionTurnId, String failureCode, String failureMessage, Instant createdAt, Instant finishedAt) { }
    public record Completion(UUID requestId, UUID summaryRevisionId, UUID outputPortId, String disposition, long revision, Instant completedAt) { }
    public record Message(UUID id, long sequence, String role, String text, UUID turnId, Instant createdAt) { }
    public record MessagePage(List<Message> messages, long nextSequence, boolean hasMore) { }
    public record State(UUID nodeRunId, String state, long revision, UUID summaryRevisionId, Revision latestRevision,
                        Turn activeTurn, Completion completion, MessagePage messages, long lastMessageSequence,
                        int turnCount, int maxTurns, Instant createdAt, Instant updatedAt) { }
}
