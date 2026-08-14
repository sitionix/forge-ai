package com.sitionix.forgeai.domain.usecase;

import java.util.UUID;

public interface DeleteAgentProject {

    void execute(UUID projectId);
}
