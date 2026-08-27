package com.sitionix.forgeai.api.agentproxy;
import jakarta.validation.constraints.NotBlank; import java.util.UUID;
public record AgentServiceRuntimeTargetRequest(@NotBlank String connection,UUID sshConnectionId,@NotBlank String provider,String container,String unit) {}
