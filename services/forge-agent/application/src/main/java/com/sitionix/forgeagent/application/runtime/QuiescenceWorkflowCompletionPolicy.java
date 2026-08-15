package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QuiescenceWorkflowCompletionPolicy implements WorkflowCompletionPolicy {

    private final NodeRunRepository nodeRunRepository;

    @Override
    public WorkflowCompletionDecision evaluate(final WorkflowRun workflowRun) {
        final java.util.List<com.sitionix.forgeagent.domain.model.NodeRun> nodeRuns = this.nodeRunRepository.findByWorkflowRunId(workflowRun.id());
        if (nodeRuns.stream().anyMatch(nodeRun -> nodeRun.status() == NodeRunStatus.PENDING || nodeRun.status() == NodeRunStatus.RUNNING)) {
            return WorkflowCompletionDecision.running();
        }
        if (nodeRuns.stream().anyMatch(nodeRun -> nodeRun.status() == NodeRunStatus.FAILED || nodeRun.status() == NodeRunStatus.BLOCKED || nodeRun.status() == NodeRunStatus.CANCELLED)) {
            return WorkflowCompletionDecision.terminal(WorkflowRunStatus.FAILED);
        }
        return WorkflowCompletionDecision.terminal(WorkflowRunStatus.SUCCEEDED);
    }
}
