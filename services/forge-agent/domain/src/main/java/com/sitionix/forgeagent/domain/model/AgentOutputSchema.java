package com.sitionix.forgeagent.domain.model;

import java.util.Objects;

public record AgentOutputSchema(String jsonObject) {

    public AgentOutputSchema {
        if (jsonObject == null || jsonObject.isBlank()) {
            throw new IllegalArgumentException("Output schema is required.");
        }
        jsonObject = jsonObject.trim();
        if (!jsonObject.startsWith("{") || !jsonObject.endsWith("}")) {
            throw new IllegalArgumentException("Output schema root must be a JSON object.");
        }
    }

    public static AgentOutputSchema ofCanonicalJsonObject(final String jsonObject) {
        return new AgentOutputSchema(Objects.requireNonNull(jsonObject, "jsonObject"));
    }
}
