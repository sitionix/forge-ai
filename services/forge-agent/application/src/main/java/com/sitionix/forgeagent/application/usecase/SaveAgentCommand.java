package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.model.AgentOutputSchema;

public record SaveAgentCommand(
        String name,
        String instructions,
        AgentOutputSchema outputSchema
) {
}
