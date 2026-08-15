package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MaximumNodeRunsExecutionBudgetPolicy implements ExecutionBudgetPolicy {

    private final NodeRunRepository nodeRunRepository;

    @Value("${forge.agent.runtime.max-node-runs-per-workflow-run:1000}")
    private int maxNodeRunsPerWorkflowRun;

    @Override
    public void assertNodeRunCanBeCreated(final WorkflowRun workflowRun) {
        if (this.maxNodeRunsPerWorkflowRun < 1) {
            return;
        }
        final int existing = this.nodeRunRepository.findByWorkflowRunId(workflowRun.id()).size();
        if (existing >= this.maxNodeRunsPerWorkflowRun) {
            throw new ConflictException("WORKFLOW_EXECUTION_BUDGET_EXCEEDED", "Workflow execution node run budget was exceeded.");
        }
    }
}
