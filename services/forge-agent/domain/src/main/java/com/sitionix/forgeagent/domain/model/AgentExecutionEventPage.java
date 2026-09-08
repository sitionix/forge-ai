package com.sitionix.forgeagent.domain.model;

import java.util.List;
import java.util.UUID;

public record AgentExecutionEventPage(
        UUID turnId,
        AgentExecutionEventCaptureStatus captureStatus,
        List<AgentExecutionEvent> events,
        long lastSequence,
        long nextAfterSequence,
        boolean hasMore) {

    public AgentExecutionEventPage {
        events = List.copyOf(events);
    }
}
