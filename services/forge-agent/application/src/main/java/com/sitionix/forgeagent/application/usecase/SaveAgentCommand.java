package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.AgentModelSelection;

public record SaveAgentCommand(
        String name,
        String instructions,
        AgentOutputSchema outputSchema,
        AgentModelSelection model
) {
    public SaveAgentCommand(final String name, final String instructions, final AgentOutputSchema outputSchema) {
        this(name, instructions, outputSchema, null);
    }
}
