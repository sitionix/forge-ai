package com.sitionix.forgeai.domain.model.operator.config;

import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadObjectContract;

public record OperatorPayloadContractResourceView(
        String payloadType,
        String resourceKey,
        CompletionPayloadObjectContract contract,
        String content
) {
}
