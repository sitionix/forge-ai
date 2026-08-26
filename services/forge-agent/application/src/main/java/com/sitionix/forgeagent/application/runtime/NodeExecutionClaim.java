package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.NodeInputEnvelope;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.RunPort;
import java.util.List;
import java.util.UUID;

public record NodeExecutionClaim(
        UUID workflowRunId,
        UUID nodeRunId,
        UUID sourceAgentId,
        String workflowInput,
        String agentName,
        String agentInstructions,
        AgentOutputSchema outputSchema,
        NodeRunExecutionModel executionModel,
        NodeInputEnvelope inputEnvelope,
        List<RunPort> availableOutputs,
        ExecutionWorkspace executionWorkspace
) {
}
