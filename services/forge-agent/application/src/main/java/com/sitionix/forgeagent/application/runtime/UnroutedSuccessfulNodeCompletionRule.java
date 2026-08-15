package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(15)
public class UnroutedSuccessfulNodeCompletionRule implements WorkflowCompletionRule {

    @Override
    public boolean supports(final WorkflowCompletionContext context) {
        return context.nodeRuns().stream()
                .anyMatch(nodeRun -> nodeRun.status() == NodeRunStatus.SUCCEEDED
                        && nodeRun.routingCompletedAt() == null
                        && nodeRun.executionFrameId() != null);
    }

    @Override
    public WorkflowCompletionDecision decision(final WorkflowCompletionContext context) {
        return new RunningWorkflowDecision();
    }
}
