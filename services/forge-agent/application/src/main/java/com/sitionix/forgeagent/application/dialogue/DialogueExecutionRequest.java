package com.sitionix.forgeagent.application.dialogue;

import com.sitionix.forgeagent.application.runtime.NodeExecutionClaim;

public record DialogueExecutionRequest(NodeExecutionClaim executionClaim) {
    public DialogueExecutionRequest {
        if (executionClaim.dialogueContext() == null || executionClaim.agentSessionClaim() == null
                || executionClaim.agentSessionClaim().contextMode() != com.sitionix.forgeagent.domain.model.NodeContextMode.DIALOGUE_WITHIN_NODE_RUN
                || !executionClaim.nodeRunId().equals(executionClaim.agentSessionClaim().nodeRunId())
                || !executionClaim.availableOutputs().isEmpty()) {
            throw new IllegalArgumentException("Dialogue execution requires an exact tracked turn without model routing.");
        }
    }
}
