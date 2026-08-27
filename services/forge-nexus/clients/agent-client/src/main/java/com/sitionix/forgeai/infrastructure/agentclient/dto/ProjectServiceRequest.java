package com.sitionix.forgeai.infrastructure.agentclient.dto;
import java.util.UUID;
public record ProjectServiceRequest(String name,UUID repositoryId,ServiceRuntimeTargetDto runtimeTarget) {}
