package com.sitionix.forgeagent.application.runtime;

public record FailedWorkflowDecision() implements WorkflowCompletionDecision {

    @Override
    public void apply(final WorkflowCompletionDecisionHandler handler) {
        handler.handle(this);
    }
}
