package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadObjectContract;
import java.util.List;

public interface ManageOperatorAgentConfig {

    OperatorAgentConfigResponse config();

    OperatorConfigResourceView saveResource(OperatorConfigResourceSaveRequest request);

    record OperatorAgentConfigResponse(
            List<OperatorAgentConfigView> agents,
            List<OperatorInstructionResourceView> instructions,
            List<OperatorPayloadContractResourceView> payloadContracts,
            List<OperatorConfigResourceView> editableResources,
            String restartRequiredMessage
    ) {
    }

    record OperatorAgentConfigView(
            String id,
            boolean enabled,
            String scopeMode,
            List<String> groups,
            List<String> dependsOn,
            List<String> produces,
            List<OperatorAgentInputPayloadView> inputPayloads,
            OperatorAgentCompletionView completion,
            OperatorLaneStrategyView laneStrategy,
            List<OperatorPayloadContractSummary> payloadContracts
    ) {
    }

    record OperatorAgentInputPayloadView(
            String sourceAgent,
            String payloadType,
            String payloadClass
    ) {
    }

    record OperatorAgentCompletionView(
            boolean writesProducedLaneOutputs,
            boolean requiresApiEvidence,
            boolean requiresOutputForEveryTarget,
            String reportPayload
    ) {
    }

    record OperatorLaneStrategyView(
            String agentId,
            int version,
            String sessionMode,
            List<OperatorLaneStrategyStepView> steps
    ) {
    }

    record OperatorLaneStrategyStepView(
            int order,
            String id,
            String title,
            String taskPlaceholder,
            String completionContractPlaceholder,
            List<String> instructionRefs
    ) {
    }

    record OperatorInstructionResourceView(
            String ref,
            String resourceKey,
            String content
    ) {
    }

    record OperatorPayloadContractResourceView(
            String payloadType,
            String resourceKey,
            CompletionPayloadObjectContract contract,
            String content
    ) {
    }

    record OperatorPayloadContractSummary(
            String payloadType,
            String payloadClass,
            String description,
            String resourceKey
    ) {
    }

    record OperatorConfigResourceView(
            String resourceKey,
            String label,
            String resourceType,
            String path,
            boolean writable,
            String content
    ) {
    }

    record OperatorConfigResourceSaveRequest(
            String resourceKey,
            String content
    ) {
    }
}
