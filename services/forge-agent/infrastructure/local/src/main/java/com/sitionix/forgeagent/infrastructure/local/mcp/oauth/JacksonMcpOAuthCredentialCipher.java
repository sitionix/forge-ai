package com.sitionix.forgeagent.infrastructure.local.mcp.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Instant;
import java.util.*;

/** Uses the existing context-bound credential row, not a parallel secret store. */
public final class JacksonMcpOAuthCredentialCipher implements McpOAuthCredentialCipher {
    private final McpCredentialCipher cipher;
    private final ObjectMapper mapper;
    public JacksonMcpOAuthCredentialCipher(McpCredentialCipher cipher, ObjectMapper mapper) { this.cipher = cipher; this.mapper = mapper; }
    public McpEncryptedCredential encrypt(UUID owner, UUID connection, McpOAuthCredentials credentials) {
        byte[] bytes = null;
        try {
            bytes = mapper.writeValueAsBytes(Payload.from(credentials));
            return cipher.encrypt(owner, connection, "credential", bytes);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalArgumentException("OAuth credential encryption unavailable"); }
        finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }
    public McpOAuthCredentials decrypt(UUID owner, UUID connection, McpEncryptedCredential encrypted) {
        byte[] bytes = cipher.decrypt(owner, connection, "credential", encrypted);
        try { return mapper.readValue(bytes, Payload.class).toDomain(); }
        catch (java.io.IOException | IllegalArgumentException exception) { throw new IllegalArgumentException("OAuth credential decryption unavailable"); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }
    private record Payload(String clientSecret, String accessToken, String refreshToken, String expiresAt,
                           String refreshExpiresAt, Set<String> scopes) {
        static Payload from(McpOAuthCredentials credentials) {
            var token = credentials.tokens();
            return token == null ? new Payload(credentials.clientSecret(), null, null, null, null, null)
                    : new Payload(credentials.clientSecret(), token.accessToken(), token.refreshToken(),
                            text(token.expiresAt()), text(token.refreshExpiresAt()), token.grantedScopes());
        }
        McpOAuthCredentials toDomain() {
            return new McpOAuthCredentials(clientSecret, accessToken == null ? null
                    : new McpOAuthTokens(accessToken, refreshToken, instant(expiresAt), instant(refreshExpiresAt), scopes));
        }
        private static String text(Instant value) { return value == null ? null : value.toString(); }
        private static Instant instant(String value) { return value == null ? null : Instant.parse(value); }
        @Override public String toString() { return "OAuthCredentialPayload[redacted]"; }
    }
}
