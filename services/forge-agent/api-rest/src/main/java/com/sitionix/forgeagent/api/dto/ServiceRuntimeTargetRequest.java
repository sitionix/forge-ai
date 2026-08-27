package com.sitionix.forgeagent.api.dto;
import com.sitionix.forgeagent.domain.model.*;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
public record ServiceRuntimeTargetRequest(@NotNull ServiceConnectionType connection, UUID sshConnectionId,
    @NotNull ServiceRuntimeProvider provider, String container, String unit) {}
