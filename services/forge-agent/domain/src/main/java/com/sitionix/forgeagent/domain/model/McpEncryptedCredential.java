package com.sitionix.forgeagent.domain.model;

import java.util.Arrays;

/** Opaque encrypted storage value; never part of MCP connection metadata. */
public final class McpEncryptedCredential {
    private final String keyId;
    private final byte[] bytes;
    public McpEncryptedCredential(String keyId, byte[] bytes) {
        if (keyId == null || keyId.isBlank() || bytes == null || bytes.length == 0) throw new IllegalArgumentException("Invalid encrypted credential");
        this.keyId = keyId;
        this.bytes = bytes.clone();
    }
    public String keyId() { return keyId; }
    public byte[] bytes() { return bytes.clone(); }
    @Override public String toString() { return "McpEncryptedCredential[redacted]"; }
    @Override public boolean equals(Object other) { return other instanceof McpEncryptedCredential c && keyId.equals(c.keyId) && Arrays.equals(bytes,c.bytes); }
    @Override public int hashCode() { return 31 * keyId.hashCode() + Arrays.hashCode(bytes); }
}
