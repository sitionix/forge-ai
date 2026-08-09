package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.List;
import java.util.UUID;

public record SaveAgentDefinitionCommand(
        String name,
        String instructions,
        AgentOutputSchemaDocument outputSchema,
        List<UUID> dependsOnAgentIds
) {
}
