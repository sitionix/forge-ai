package com.sitionix.forgeai.api.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentLogConnectionType;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AgentLogSourceRequest(
    @NotBlank String name,
    UUID serviceId,
    @NotNull AgentLogConnectionType connection,
    UUID sshConnectionId,
    @NotNull AgentLogProviderType provider,
    String container,
    String composeService,
    String composeFile,
    String unit,
    String path,
    boolean enabled) {}
