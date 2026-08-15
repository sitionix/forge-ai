package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import java.util.List;

public record WorkflowCompletionContext(
        WorkflowRun workflowRun,
        List<NodeRun> nodeRuns,
        boolean hasOpenActivation
) {
}
