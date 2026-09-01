package com.sitionix.forgeagent.api.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
public record ProjectAssetRequest(@NotBlank String name, @NotNull UUID sshConnectionId) {}
