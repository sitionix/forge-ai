package com.sitionix.forgeagent.application.runtime;

public interface WorkflowCompletionRule {

    boolean supports(WorkflowCompletionContext context);

    WorkflowCompletionDecision decision(WorkflowCompletionContext context);
}
