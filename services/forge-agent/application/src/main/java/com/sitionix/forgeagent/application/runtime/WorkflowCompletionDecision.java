package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.WorkflowRunStatus;

public record WorkflowCompletionDecision(boolean terminal, WorkflowRunStatus status) {

    public static WorkflowCompletionDecision running() {
        return new WorkflowCompletionDecision(false, null);
    }

    public static WorkflowCompletionDecision terminal(final WorkflowRunStatus status) {
        return new WorkflowCompletionDecision(true, status);
    }
}
