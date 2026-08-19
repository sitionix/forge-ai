package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.List;
import java.util.UUID;

public record CreateProjectTaskRequest(String title, String input, UUID workflowId, List<UUID> repositoryIds) {
}
