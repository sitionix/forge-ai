package com.sitionix.forgeagent.domain.model;

import java.util.UUID;

/** A reusable authoring document; a null definition identifies legacy summary-only rows. */
public record TriggerConfigurationDocument(UUID id, UUID connectionId, String name, String definitionJson) {}
