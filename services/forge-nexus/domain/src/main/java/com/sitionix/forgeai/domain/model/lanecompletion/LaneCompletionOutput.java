package com.sitionix.forgeai.domain.model.lanecompletion;

import java.util.Map;

public record LaneCompletionOutput(
        String agent,
        String scope,
        Boolean required,
        Map<String, Object> payload
) {

    public boolean isRequired() {
        return this.required == null || this.required;
    }
}
