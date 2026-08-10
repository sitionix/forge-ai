package com.sitionix.forgeai.api.agentproxy;

import jakarta.validation.constraints.NotBlank;

public record CreateAgentWorkflowRunRequest(@NotBlank String input) {
}
