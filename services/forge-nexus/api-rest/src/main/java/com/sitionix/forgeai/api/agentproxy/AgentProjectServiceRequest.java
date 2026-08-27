package com.sitionix.forgeai.api.agentproxy;
import jakarta.validation.Valid; import jakarta.validation.constraints.*; import java.util.UUID;
public record AgentProjectServiceRequest(@NotBlank String name,UUID repositoryId,@NotNull @Valid AgentServiceRuntimeTargetRequest runtimeTarget) {}
