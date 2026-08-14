package com.sitionix.forgeai.domain.usecase;

import java.util.UUID;

public interface DeleteAgentWorkflow {

    void execute(UUID workflowId);
}
