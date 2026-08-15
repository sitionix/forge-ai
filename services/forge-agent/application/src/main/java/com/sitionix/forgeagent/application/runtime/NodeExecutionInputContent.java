package com.sitionix.forgeagent.application.runtime;

import java.util.List;

public record NodeExecutionInputContent(
        String workflowInput,
        List<NodeDependencyOutput> dependencies
) {
}
