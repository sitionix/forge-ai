package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;
import java.util.UUID;

public record AgentExecutionEventPage(
        UUID turnId, String captureStatus, List<AgentExecutionEvent> events,
        long lastSequence, long nextAfterSequence, boolean hasMore) {

    public AgentExecutionEventPage {
        events = List.copyOf(events);
    }
}
