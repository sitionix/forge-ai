package com.sitionix.forgeagent.application.usecase;
import com.sitionix.forgeagent.domain.model.ServiceRuntimeTarget;
import java.util.UUID;
public record SaveProjectServiceCommand(String name, UUID repositoryId, ServiceRuntimeTarget runtimeTarget) {}
