package com.sitionix.forgeagent.domain.model;

import java.util.Map;
import java.util.Locale;
import java.util.stream.Collectors;

/** Launch-only scope-limited bearers; never serialize or log this object. */
public final class McpRuntimeLaunchGrants {
    private final Map<String, String> tokens;

    public McpRuntimeLaunchGrants(Map<String, String> tokens) {
        if (tokens == null || tokens.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || !entry.getKey().matches("forge_[0-9a-f]{32}")
                        || entry.getValue() == null))
            throw new IllegalArgumentException("Invalid MCP launch grants");
        this.tokens = Map.copyOf(tokens);
    }

    public Map<String, String> tokens() { return tokens; }
    public boolean isEmpty() { return tokens.isEmpty(); }

    public Map<String, String> environment() {
        return tokens.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                entry -> "FORGE_MCP_GRANT_" + entry.getKey().substring("forge_".length())
                        .toUpperCase(Locale.ROOT), Map.Entry::getValue));
    }

    @Override public String toString() { return "McpRuntimeLaunchGrants[REDACTED]"; }
}
