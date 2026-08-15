package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.ExecutionFrame;
import com.sitionix.forgeagent.domain.model.RunNode;
import com.sitionix.forgeagent.domain.model.WorkflowRun;

public interface FrameTransitionPolicy {

    ExecutionFrame frameForActivation(WorkflowRun workflowRun, ExecutionFrame activationFrame, RunNode targetNode);
}
