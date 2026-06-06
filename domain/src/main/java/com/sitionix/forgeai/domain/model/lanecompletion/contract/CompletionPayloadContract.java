package com.sitionix.forgeai.domain.model.lanecompletion.contract;

import java.util.List;

public record CompletionPayloadContract(
        List<CompletionOutputContract> outputs,
        boolean apiEvidenceRequired,
        CompletionPayloadObjectContract apiEvidence,
        CompletionPayloadObjectContract report
) {
}
