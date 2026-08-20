package com.sitionix.forgeagent.domain.model;

import java.util.UUID;

public record NodeInputContribution(
        UUID sourceNodeRunId,
        UUID sourceConnectionId,
        NodeRunOutput payload,
        UUID sourceRepositoryId
) {
}
