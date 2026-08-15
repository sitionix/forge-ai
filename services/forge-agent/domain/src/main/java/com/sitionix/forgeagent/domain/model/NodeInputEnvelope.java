package com.sitionix.forgeagent.domain.model;

import java.util.List;

public record NodeInputEnvelope(
        String originalTask,
        RunPort entryInputPort,
        List<NodeInputContribution> contributions
) {
}
