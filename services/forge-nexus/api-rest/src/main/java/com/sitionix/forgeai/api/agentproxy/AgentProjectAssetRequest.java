package com.sitionix.forgeai.api.agentproxy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AgentProjectAssetRequest(
        @NotBlank String name,
        @NotNull UUID sshConnectionId) {
}
