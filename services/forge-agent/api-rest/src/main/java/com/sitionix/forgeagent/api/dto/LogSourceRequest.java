package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record LogSourceRequest(@NotBlank String name, UUID serviceId,
 @NotNull LogConnectionType connection, UUID sshConnectionId, @NotNull LogProviderType provider,
 String container, String composeService, String composeFile, String unit, String path, boolean enabled) { }
