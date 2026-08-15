package com.sitionix.forgeagent.application.runtime;

public interface WorkflowCompletionDecision {

    void apply(WorkflowCompletionDecisionHandler handler);
}
