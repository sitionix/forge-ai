package com.sitionix.forgeai.infrastructure.agentclient.dto;
import java.util.UUID;
public record ServiceRuntimeTargetDto(String connection,UUID sshConnectionId,String provider,String container,String unit) {}
