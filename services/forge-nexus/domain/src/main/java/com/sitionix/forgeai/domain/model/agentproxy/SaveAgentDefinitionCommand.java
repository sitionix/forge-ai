package com.sitionix.forgeai.domain.model.agentproxy;

public record SaveAgentDefinitionCommand(
        String name,
        String instructions,
        AgentOutputSchemaDocument outputSchema,
        AgentModelSelection model
) {
}
