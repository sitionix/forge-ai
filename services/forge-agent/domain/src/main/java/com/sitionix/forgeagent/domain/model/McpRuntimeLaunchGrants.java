package com.sitionix.forgeagent.domain.model;

import java.util.Map;

/** Launch-only scope-limited bearers; never serialize or log this object. */
public final class McpRuntimeLaunchGrants {
    private final Map<String, String> tokens;

    public McpRuntimeLaunchGrants(Map<String, String> tokens) {
        if (tokens == null || tokens.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getValue() == null))
            throw new IllegalArgumentException("Invalid MCP launch grants");
        this.tokens = Map.copyOf(tokens);
    }

    public Map<String, String> tokens() { return tokens; }
    public boolean isEmpty() { return tokens.isEmpty(); }

    @Override public String toString() { return "McpRuntimeLaunchGrants[REDACTED]"; }
}
