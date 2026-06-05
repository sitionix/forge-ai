package com.sitionix.forgeai.domain.model.lanecompletion.contract;

public record CompletionOutputContract(
        String agent,
        String scope,
        boolean required,
        CompletionPayloadObjectContract payload
) {
}
