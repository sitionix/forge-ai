package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.List;
import java.util.UUID;

public record AgentExecutionEventPageResponse(
        UUID turnId, String captureStatus, List<AgentExecutionEventResponse> events,
        long lastSequence, long nextAfterSequence, boolean hasMore) {
}
