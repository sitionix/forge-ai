package com.sitionix.forgeai.api.agentproxy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateAgentProjectTaskRequest(@NotBlank String title, @NotBlank String input, @NotNull UUID workflowId) {
}
