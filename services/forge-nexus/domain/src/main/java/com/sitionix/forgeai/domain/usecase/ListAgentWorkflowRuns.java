package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentWorkflowRunSummary;
import java.util.List;
import java.util.UUID;

public interface ListAgentWorkflowRuns {

    List<AgentWorkflowRunSummary> execute(UUID workflowId);
}
