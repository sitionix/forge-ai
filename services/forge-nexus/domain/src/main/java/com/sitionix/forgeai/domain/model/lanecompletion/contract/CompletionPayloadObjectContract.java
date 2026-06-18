package com.sitionix.forgeai.domain.model.lanecompletion.contract;

import java.util.List;

public record CompletionPayloadObjectContract(
        String payloadType,
        String description,
        List<CompletionPayloadFieldContract> fields
) {
}
