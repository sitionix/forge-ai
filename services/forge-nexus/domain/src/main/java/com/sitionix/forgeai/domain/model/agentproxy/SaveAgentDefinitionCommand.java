package com.sitionix.forgeai.domain.model.agentproxy;

public record SaveAgentDefinitionCommand(
        String name,
        String instructions,
        AgentOutputSchemaDocument outputSchema,
        AgentModelSelection model
) {
    public SaveAgentDefinitionCommand(final String name,
                                      final String instructions,
                                      final AgentOutputSchemaDocument outputSchema) {
        this(name, instructions, outputSchema, null);
    }
}
