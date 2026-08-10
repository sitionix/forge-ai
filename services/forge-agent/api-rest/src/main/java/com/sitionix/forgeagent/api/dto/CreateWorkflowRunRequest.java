package com.sitionix.forgeagent.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateWorkflowRunRequest(@NotBlank String input) {
}
