package com.sitionix.forgeagent.application.runtime;

public interface AgentExecutor {

    AgentExecutionResult execute(NodeExecutionClaim claim);

    default void cancel(final NodeExecutionClaim claim) {
        // Most executors do not own an external process. Providers with a cancellation
        // boundary override this so lease fencing can stop active side effects.
    }
}
