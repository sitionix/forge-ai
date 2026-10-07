package com.sitionix.forgeagent.application.dialogue;

import com.sitionix.forgeagent.domain.model.*;
import java.time.Instant;
import java.util.UUID;

final class DialogueNodeTransitions {
    private DialogueNodeTransitions() { }

    static NodeRun node(final NodeRun n, final NodeRunStatus status, final Instant now,
                        final NodeRunOutput output, final NodeRunFailure failure, final UUID selectedPort) {
        boolean terminal = !status.active();
        return new NodeRun(n.id(),n.workflowRunId(),n.sourceNodeId(),n.sourceAgentId(),n.agentName(),n.agentInstructions(),
                n.agentOutputSchema(),n.inputMode(),n.position(),n.executionFrameId(),n.enteredViaInputPortId(),
                n.activationFrameId(),selectedPort,n.routingCompletedAt(),status,output,failure,n.executionModel(),
                n.createdAt(),n.startedAt()==null ? now : n.startedAt(),terminal ? now : null,n.repositoryId(),
                n.contextMode(),n.contextTrackingVersion(),n.retryOfNodeRunId(),n.contextGroupKey(),n.contextIterationId(),n.nodeType());
    }

    static WorkflowRun running(final WorkflowRun r, final Instant now) {
        return new WorkflowRun(r.id(),r.projectId(),r.sourceWorkflowId(),r.taskId(),r.workflowName(),r.input(),
                WorkflowRunStatus.RUNNING,r.nodeRuns(),r.connectionResolutions(),r.executionEdges(),r.runtimeGraph(),
                r.result(),r.resultSourceNodeRunId(),r.createdAt(),r.startedAt()==null ? now : r.startedAt(),r.finishedAt(),
                r.repositoryIds(),r.operatorStopStatus(),r.operatorStopFailureCode(),r.operatorStopPendingNodeRunIds(),r.operatorStopAttempt());
    }
}
