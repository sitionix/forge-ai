package com.sitionix.forgeagent.infrastructure.local.mcp.oauth;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.infrastructure.local.mcp.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class JacksonMcpOAuthCredentialCipherTest {
    @Test void encryptsTypedSecretsAndPreservesContextBinding() throws Exception {
        var cipher = new JacksonMcpOAuthCredentialCipher(new AesGcmMcpCredentialCipher(
                () -> new McpLocalKeys("fixture", Map.of("fixture", new byte[32]))), new ObjectMapper());
        var owner = UUID.randomUUID(); var id = UUID.randomUUID();
        var expiry = Instant.parse("2026-09-29T10:00:00Z");
        var secret = new McpOAuthCredentials("client-canary", new McpOAuthTokens("access-canary", "refresh-canary", expiry, null, Set.of("tools")));
        var encrypted = cipher.encrypt(owner, id, secret);
        assertThat(new String(encrypted.bytes(), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("canary");
        var restored = cipher.decrypt(owner, id, encrypted);
        assertThat(restored.clientSecret()).isEqualTo("client-canary");
        assertThat(restored.tokens().accessToken()).isEqualTo("access-canary");
        assertThat(restored.tokens().refreshToken()).isEqualTo("refresh-canary");
        assertThat(restored.tokens().expiresAt()).isEqualTo(expiry);
        assertThat(restored.tokens().refreshExpiresAt()).isNull();
        assertThat(restored.toString()).doesNotContain("canary");
        assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), id, encrypted)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decrypt(owner, UUID.randomUUID(), encrypted)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ObjectMapper().writeValueAsString(secret)).isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidDefinitionException.class);
    }

    @Test void setupWithoutTokensIsEncryptedWithoutInventingAccess() {
        var cipher = new JacksonMcpOAuthCredentialCipher(new AesGcmMcpCredentialCipher(
                () -> new McpLocalKeys("fixture", Map.of("fixture", new byte[32]))), new ObjectMapper());
        var owner = UUID.randomUUID(); var id = UUID.randomUUID();
        var restored = cipher.decrypt(owner, id, cipher.encrypt(owner, id, new McpOAuthCredentials("client-canary", null)));
        assertThat(restored.tokens()).isNull();
    }
}
