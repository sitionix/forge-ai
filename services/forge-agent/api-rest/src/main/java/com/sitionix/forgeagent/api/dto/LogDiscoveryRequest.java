package com.sitionix.forgeagent.api.dto;
import com.sitionix.forgeagent.domain.model.*;import jakarta.validation.constraints.NotNull;import java.util.UUID;
public record LogDiscoveryRequest(@NotNull LogConnectionType connection,UUID sshConnectionId,@NotNull LogProviderType provider,UUID serviceId) { }
