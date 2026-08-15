package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.WorkflowRun;

public interface ExecutionBudgetPolicy {

    void assertNodeRunCanBeCreated(WorkflowRun workflowRun);
}
