package com.sitionix.forgeagent.application.runtime;

import java.util.UUID;

public interface InputParticipationResolver {

    InputParticipation resolve(UUID workflowRunId, UUID activationFrameId, UUID targetInputPortId);
}
