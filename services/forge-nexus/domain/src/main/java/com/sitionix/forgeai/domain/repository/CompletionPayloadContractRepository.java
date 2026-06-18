package com.sitionix.forgeai.domain.repository;

import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadObjectContract;

public interface CompletionPayloadContractRepository {

    CompletionPayloadObjectContract findByType(Class<?> payloadType);

    CompletionPayloadObjectContract findByTypeName(String payloadType);
}
