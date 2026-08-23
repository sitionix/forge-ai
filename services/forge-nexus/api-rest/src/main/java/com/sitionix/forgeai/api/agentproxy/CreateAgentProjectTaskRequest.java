package com.sitionix.forgeai.api.agentproxy;

import java.util.List;
import java.util.UUID;

public record CreateAgentProjectTaskRequest(String title, String input, UUID workflowId,
                                            List<UUID> repositoryIds) {
}
