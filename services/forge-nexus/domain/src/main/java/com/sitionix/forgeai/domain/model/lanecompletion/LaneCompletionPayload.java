package com.sitionix.forgeai.domain.model.lanecompletion;

import java.util.List;
import java.util.Map;

public record LaneCompletionPayload(
        List<LaneCompletionOutput> outputs,
        ApiCompletionEvidence apiEvidence,
        Map<String, Object> report
) {
}
