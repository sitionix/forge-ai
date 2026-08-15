package com.sitionix.forgeagent.application.runtime;

public record RunningWorkflowDecision() implements WorkflowCompletionDecision {

    @Override
    public void apply(final WorkflowCompletionDecisionHandler handler) {
        handler.handle(this);
    }
}
