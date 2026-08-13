package com.sitionix.forgeagent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateProjectTaskRequest(@NotBlank String title, @NotBlank String input, @NotNull UUID workflowId) {
}
