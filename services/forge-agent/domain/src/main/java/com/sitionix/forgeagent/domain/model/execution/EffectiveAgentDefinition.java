package com.sitionix.forgeagent.domain.model.execution;

import com.sitionix.forgeagent.domain.model.AgentModelSelection;
import java.util.Map;
import java.util.Objects;

/** Private, immutable execution snapshot. Never serialize this value as a public API response. */
public record EffectiveAgentDefinition(String source, String reference, String name, String instructions,
        AgentModelSelection model, String fingerprint, Map<String,String> resources, boolean nativeSubagents,String sourceOutputSchemaJson) {
    public EffectiveAgentDefinition(String source,String reference,String name,String instructions,AgentModelSelection model,String fingerprint,Map<String,String> resources,boolean nativeSubagents) {
        this(source,reference,name,instructions,model,fingerprint,resources,nativeSubagents,null);
    }
    public EffectiveAgentDefinition {
        Objects.requireNonNull(source); Objects.requireNonNull(reference); Objects.requireNonNull(instructions);
        Objects.requireNonNull(model); Objects.requireNonNull(fingerprint);
        resources = Map.copyOf(resources);
    }
}
