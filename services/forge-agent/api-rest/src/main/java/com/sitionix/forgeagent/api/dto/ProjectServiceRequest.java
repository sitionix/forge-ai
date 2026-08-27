package com.sitionix.forgeagent.api.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
public record ProjectServiceRequest(@NotBlank @Size(max=120) String name, UUID repositoryId,
    @NotNull @Valid ServiceRuntimeTargetRequest runtimeTarget) {}
