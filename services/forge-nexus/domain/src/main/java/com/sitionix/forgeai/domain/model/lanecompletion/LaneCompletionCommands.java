package com.sitionix.forgeai.domain.model.lanecompletion;

import java.util.Map;
import java.util.UUID;

public final class LaneCompletionCommands {

    private LaneCompletionCommands() {
    }

    public record CompleteLane(
            UUID ticketId,
            UUID laneId,
            Map<String, Object> completionPayload
    ) {
    }
}
