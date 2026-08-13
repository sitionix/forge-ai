package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.UUID;

public record CreateProjectTaskRequest(String title, String input, UUID workflowId) {
}
