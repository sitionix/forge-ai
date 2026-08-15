package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.WorkflowRun;
import org.springframework.stereotype.Component;

@Component
public class PermissiveExecutionBudgetPolicy implements ExecutionBudgetPolicy {

    @Override
    public void assertNodeRunCanBeCreated(final WorkflowRun workflowRun) {
    }
}
