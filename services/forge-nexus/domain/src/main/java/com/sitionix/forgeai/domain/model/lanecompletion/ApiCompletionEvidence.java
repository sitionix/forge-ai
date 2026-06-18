package com.sitionix.forgeai.domain.model.lanecompletion;

import java.util.List;

public record ApiCompletionEvidence(
        String summary,
        String prUrl,
        String repo,
        List<ApiCompletionContractResult> contracts
) {
}
