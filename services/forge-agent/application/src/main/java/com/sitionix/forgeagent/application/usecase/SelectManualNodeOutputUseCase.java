package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.application.runtime.ManualNodeRunLifecycle;
import com.sitionix.forgeagent.application.runtime.NodeRunCompletionProcessor;
import com.sitionix.forgeagent.domain.model.WorkflowRun;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SelectManualNodeOutputUseCase {
    private final ManualNodeRunLifecycle lifecycle;
    private final NodeRunCompletionProcessor completion;
    private final WorkflowRunUseCases runs;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public WorkflowRun execute(final UUID workflowRunId, final UUID nodeRunId, final UUID outputPortId) {
        this.lifecycle.selectOutput(workflowRunId, nodeRunId, outputPortId);
        // The decision is committed first; the completion worker can recover a crash here.
        this.completion.process(nodeRunId);
        return this.runs.getWorkflowRun(workflowRunId);
    }
}
