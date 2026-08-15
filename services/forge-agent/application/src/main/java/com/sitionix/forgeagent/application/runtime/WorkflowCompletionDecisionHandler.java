package com.sitionix.forgeagent.application.runtime;

public interface WorkflowCompletionDecisionHandler {

    void handle(RunningWorkflowDecision decision);

    void handle(SuccessfulWorkflowDecision decision);

    void handle(FailedWorkflowDecision decision);
}
