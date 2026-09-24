package com.sitionix.forgeagent.infrastructure.local.mcp;

import java.util.*;

public final class McpLocalKeys {
    private final String activeId;
    private final Map<String,byte[]> keys;
    public McpLocalKeys(String activeId, Map<String,byte[]> keys) {
        if (activeId == null || activeId.isBlank() || keys == null || !keys.containsKey(activeId))
            throw new IllegalArgumentException("MCP key configuration unavailable");
        Map<String,byte[]> copy = new HashMap<>();
        keys.forEach((id, bytes) -> {
            if (id == null || id.isBlank() || bytes == null || bytes.length != 32)
                throw new IllegalArgumentException("MCP key configuration unavailable");
            copy.put(id,bytes.clone());
        });
        this.activeId = activeId;
        this.keys = Map.copyOf(copy);
    }
    public String activeId() { return activeId; }
    public byte[] key(String id) { byte[] value = keys.get(id); return value == null ? null : value.clone(); }
    @Override public String toString() { return "McpLocalKeys[redacted]"; }
}
