package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LaneCompletionDispatcher {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public LaneCompletionDispatcher(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validateFinalCompletionPayload(final ReadyToStartLane lane, final Map<String, Object> evidence) {
        lane.getAgent().validateFinalCompletionPayload(lane, this.requireCompletionPayload(evidence));
    }

    public void completeLane(final ReadyToStartLane lane, final Map<String, Object> evidence) {
        lane.getAgent().completeLane(lane, this.requireCompletionPayload(evidence));
    }

    private Map<String, Object> requireCompletionPayload(final Map<String, Object> evidence) {
        final Object value = evidence == null ? null : evidence.get("completionPayload");
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Missing object field: completionPayload");
        }
        return this.objectMapper.convertValue(map, MAP_TYPE);
    }
}
