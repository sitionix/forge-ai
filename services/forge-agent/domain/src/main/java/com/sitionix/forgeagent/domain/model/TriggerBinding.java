package com.sitionix.forgeagent.domain.model;
import java.util.UUID;
public record TriggerBinding(UUID nodeId, UUID connectionId, UUID configurationId) { }
