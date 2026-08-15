package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.ConnectionResolution;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import java.util.List;

public record NodeInputContentContext(
        WorkflowRun workflowRun,
        NodeRun nodeRun,
        List<ConnectionResolution> consumedContributions
) {
}
