package com.sitionix.forgeagent.application.usecase;

import java.util.List;
import java.util.UUID;

public record CreateProjectTaskCommand(String title, String input, UUID workflowId, List<UUID> repositoryIds) {
}
