package com.sitionix.forgeai.domain.model.operator.config;

import java.util.List;

public record OperatorAgentConfigResponse(
        List<OperatorAgentConfigView> agents,
        List<OperatorInstructionResourceView> instructions,
        List<OperatorPayloadContractResourceView> payloadContracts,
        List<OperatorConfigResourceView> editableResources,
        String restartRequiredMessage
) {
}
