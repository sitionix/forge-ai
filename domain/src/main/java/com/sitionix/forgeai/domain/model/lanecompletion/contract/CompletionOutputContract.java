package com.sitionix.forgeai.domain.model.lanecompletion.contract;

public record CompletionOutputContract(
        String agent,
        String scope,
        String payloadScope,
        boolean required,
        CompletionPayloadObjectContract payload
) {
}
