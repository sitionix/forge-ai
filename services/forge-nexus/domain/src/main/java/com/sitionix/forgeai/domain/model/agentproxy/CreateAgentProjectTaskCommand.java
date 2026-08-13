package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;

public record CreateAgentProjectTaskCommand(String title, String input, UUID workflowId) {
}
