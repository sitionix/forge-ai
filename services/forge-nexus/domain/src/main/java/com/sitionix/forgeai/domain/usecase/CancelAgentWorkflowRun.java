package com.sitionix.forgeai.domain.usecase;

import java.util.UUID;

public interface CancelAgentWorkflowRun {
    void execute(UUID runId);
}
