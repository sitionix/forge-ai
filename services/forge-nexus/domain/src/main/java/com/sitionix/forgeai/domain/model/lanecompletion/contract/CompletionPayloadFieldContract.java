package com.sitionix.forgeai.domain.model.lanecompletion.contract;

public record CompletionPayloadFieldContract(
        String name,
        CompletionPayloadValueType type,
        boolean required,
        String description,
        CompletionPayloadValueType itemType,
        String objectType,
        String itemObjectType
) {
}
