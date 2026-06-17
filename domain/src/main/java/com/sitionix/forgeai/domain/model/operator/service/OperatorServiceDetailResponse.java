package com.sitionix.forgeai.domain.model.operator.service;

import java.util.List;

public record OperatorServiceDetailResponse(
        OperatorServiceSummary service,
        List<OperatorServiceContractReference> contractReferences,
        OperatorServiceDatabase database
) {
}
