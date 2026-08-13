package com.sitionix.forgeagent.application.usecase;

import java.util.UUID;

public record CreateProjectTaskCommand(String title, String input, UUID workflowId) {
}
