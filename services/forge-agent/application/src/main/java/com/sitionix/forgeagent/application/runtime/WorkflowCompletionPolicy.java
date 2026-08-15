package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.WorkflowRun;

public interface WorkflowCompletionPolicy {

    WorkflowCompletionDecision evaluate(WorkflowRun workflowRun);
}
