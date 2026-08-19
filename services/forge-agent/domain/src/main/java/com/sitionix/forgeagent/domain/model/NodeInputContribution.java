package com.sitionix.forgeagent.domain.model;

import java.util.UUID;

public record NodeInputContribution(
        UUID sourceNodeRunId,
        UUID sourceConnectionId,
        NodeRunOutput payload,
        UUID sourceRepositoryId
) {
    public NodeInputContribution(final UUID sourceNodeRunId, final UUID sourceConnectionId, final NodeRunOutput payload) {
        this(sourceNodeRunId, sourceConnectionId, payload, null);
    }
}
