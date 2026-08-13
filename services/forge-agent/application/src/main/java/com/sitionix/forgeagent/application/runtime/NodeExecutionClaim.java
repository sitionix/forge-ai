package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
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
        NodeInputMode inputMode,
        List<NodeDependencyOutput> dependencies
) {
    public NodeExecutionClaim(final UUID workflowRunId,
                              final UUID nodeRunId,
                              final UUID sourceAgentId,
                              final String workflowInput,
                              final String agentName,
                              final String agentInstructions,
                              final AgentOutputSchema outputSchema,
                              final NodeRunExecutionModel executionModel,
                              final List<NodeDependencyOutput> dependencies) {
        this(
                workflowRunId,
                nodeRunId,
                sourceAgentId,
                workflowInput,
                agentName,
                agentInstructions,
                outputSchema,
                executionModel,
                NodeInputMode.DEPENDENCIES_ONLY,
                dependencies
        );
    }
}
