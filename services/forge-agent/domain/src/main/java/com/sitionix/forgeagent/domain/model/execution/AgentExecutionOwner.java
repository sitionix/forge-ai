package com.sitionix.forgeagent.domain.model.execution;
import java.util.*;
public sealed interface AgentExecutionOwner permits AgentExecutionOwner.Workflow,AgentExecutionOwner.Standalone {
 record Workflow(UUID workflowRunId,UUID nodeRunId,UUID executionTurnId) implements AgentExecutionOwner { public Workflow {Objects.requireNonNull(workflowRunId);Objects.requireNonNull(nodeRunId);} }
 record Standalone(UUID projectId,UUID sessionId,UUID turnId) implements AgentExecutionOwner { public Standalone {Objects.requireNonNull(projectId);Objects.requireNonNull(sessionId);Objects.requireNonNull(turnId);} }
}
