package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import java.util.List;
import java.util.UUID;

public record SaveAgentCommand(
        String name,
        String instructions,
        AgentOutputSchema outputSchema,
        List<UUID> dependsOnAgentIds
) {
}
