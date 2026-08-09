package com.sitionix.forgeai.api.agentproxy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentWorkflowRequest(
        @NotBlank @Size(max = 120) String name
) {
}
