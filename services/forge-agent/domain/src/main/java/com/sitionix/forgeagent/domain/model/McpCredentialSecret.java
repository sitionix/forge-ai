package com.sitionix.forgeagent.domain.model;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit, write-only credential input. No JavaBean getters or default serialization surface. */
public final class McpCredentialSecret {
    private static final Set<String> FORBIDDEN = Set.of("host", "cookie", "authorization", "connection", "keep-alive", "proxy-connection", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "forwarded", "via", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "content-length");
    private final McpAuthType type;
    private final byte[] value;
    private McpCredentialSecret(McpAuthType type, byte[] value) { this.type = type; this.value = value.clone(); }
    public static McpCredentialSecret bearer(String token) {
        if (token == null || token.isBlank() || masked(token) || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid credential");
        return new McpCredentialSecret(McpAuthType.BEARER, token.getBytes(StandardCharsets.UTF_8));
    }
    public static McpCredentialSecret headers(Map<String,String> headers) {
        if (headers == null || headers.isEmpty()) throw new IllegalArgumentException("Invalid credential");
        try {
            var output = new ByteArrayOutputStream(); var data = new DataOutputStream(output);
            data.writeInt(headers.size());
            for (var entry : new TreeMap<>(headers).entrySet()) {
                String name = entry.getKey(), value = entry.getValue();
                if (name == null || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || FORBIDDEN.contains(name.toLowerCase(Locale.ROOT))
                        || name.toLowerCase(Locale.ROOT).startsWith("x-forwarded-") || value == null || value.isBlank()
                        || masked(value) || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
                    throw new IllegalArgumentException("Invalid credential");
                data.writeUTF(name); data.writeUTF(value);
            }
            return new McpCredentialSecret(McpAuthType.SECRET_HEADERS, output.toByteArray());
        } catch (IOException ex) { throw new IllegalArgumentException("Invalid credential"); }
    }
    private static boolean masked(String value) { return value.strip().matches("(?:\\*{4,}|•{4,})"); }
    public McpAuthType type() { return type; }
    public byte[] bytes() { return value.clone(); }
    @Override public String toString() { return "McpCredentialSecret[redacted]"; }
}
