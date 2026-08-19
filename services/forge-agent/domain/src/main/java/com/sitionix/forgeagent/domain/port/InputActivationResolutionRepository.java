package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.InputActivationResolution;
import java.util.Optional;
import java.util.UUID;

public interface InputActivationResolutionRepository {

    InputActivationResolution save(InputActivationResolution resolution);

    Optional<InputActivationResolution> find(UUID workflowRunId, UUID activationFrameId, UUID targetInputPortId, UUID repositoryId);

    default Optional<InputActivationResolution> find(final UUID workflowRunId, final UUID activationFrameId,
                                                     final UUID targetInputPortId) {
        return this.find(workflowRunId, activationFrameId, targetInputPortId, null);
    }
}
