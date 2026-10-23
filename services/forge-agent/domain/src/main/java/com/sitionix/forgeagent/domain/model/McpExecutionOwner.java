package com.sitionix.forgeagent.domain.model;
import java.util.*;
public sealed interface McpExecutionOwner permits McpExecutionOwner.Workflow,McpExecutionOwner.Standalone {
    record Workflow(UUID nodeRunId,UUID workflowRunId) implements McpExecutionOwner {
        public Workflow { Objects.requireNonNull(nodeRunId);Objects.requireNonNull(workflowRunId); }
    }
    record Standalone(UUID sessionId,UUID turnId) implements McpExecutionOwner {
        public Standalone { Objects.requireNonNull(sessionId);Objects.requireNonNull(turnId); }
    }
}
