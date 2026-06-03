package com.sitionix.forgeai.domain.model.codex;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder(toBuilder = true)
public record CodexProgressEvent(
        UUID executionId,
        UUID ticketId,
        UUID laneId,
        String agentId,
        String scope,
        String sessionId,
        String threadId,
        String turnId,
        String stepId,
        Integer stepOrder,
        String stepTitle,
        String itemId,
        Long processPid,
        String command,
        String cwd,
        String codexVersion,
        CodexProgressEventType eventType,
        String status,
        String stream,
        String text,
        Integer chars,
        Integer fileCount,
        Long durationMs,
        Instant occurredAt
) {
}
