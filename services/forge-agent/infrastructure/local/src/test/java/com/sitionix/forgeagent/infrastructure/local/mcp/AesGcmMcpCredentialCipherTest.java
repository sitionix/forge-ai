package com.sitionix.forgeagent.infrastructure.local.mcp;

import static org.assertj.core.api.Assertions.*;

import com.sitionix.forgeagent.domain.model.McpEncryptedCredential;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AesGcmMcpCredentialCipherTest {
    private static final byte[] KEY_A = new byte[32];
    private static final byte[] KEY_B = new byte[32];
    private final UUID installation = UUID.randomUUID();
    private final UUID connection = UUID.randomUUID();

    @Test void roundTripAndContextBinding() {
        var cipher = new AesGcmMcpCredentialCipher(() -> new McpLocalKeys("a", Map.of("a", KEY_A)));
        var encrypted = cipher.encrypt(installation, connection, "credential", "secret".getBytes());
        assertThat(new String(cipher.decrypt(installation, connection, "credential", encrypted))).isEqualTo("secret");
        assertThatThrownBy(() -> cipher.decrypt(installation, UUID.randomUUID(), "credential", encrypted)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
        assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), connection, "credential", encrypted)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decrypt(installation, connection, "other", encrypted)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void tamperWrongKeyAndRotation() {
        var old = new AesGcmMcpCredentialCipher(() -> new McpLocalKeys("a", Map.of("a", KEY_A)));
        var encrypted = old.encrypt(installation, connection, "credential", "secret".getBytes());
        KEY_B[0] = 42;
        var wrong = new AesGcmMcpCredentialCipher(() -> new McpLocalKeys("a", Map.of("a", KEY_B)));
        assertThatThrownBy(() -> wrong.decrypt(installation, connection, "credential", encrypted)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
        var rotated = new AesGcmMcpCredentialCipher(() -> new McpLocalKeys("b", Map.of("a", KEY_A, "b", KEY_B)));
        var newEncrypted = rotated.encrypt(installation, connection, "credential", rotated.decrypt(installation, connection, "credential", encrypted));
        assertThat(newEncrypted.keyId()).isEqualTo("b");
        assertThat(new String(rotated.decrypt(installation, connection, "credential", newEncrypted))).isEqualTo("secret");
        assertThatThrownBy(() -> rotated.decrypt(installation, connection, "credential", new McpEncryptedCredential("missing", encrypted.bytes())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
        assertThatThrownBy(() -> new AesGcmMcpCredentialCipher(() -> new McpLocalKeys("b", Map.of("b", KEY_B)))
                .decrypt(installation, connection, "credential", encrypted)).isInstanceOf(IllegalArgumentException.class);
        byte[] changed = newEncrypted.bytes(); changed[changed.length - 1] ^= 1;
        assertThatThrownBy(() -> rotated.decrypt(installation, connection, "credential", new McpEncryptedCredential("b", changed))).isInstanceOf(IllegalArgumentException.class);
        assertThat(encrypted.toString()).doesNotContain("secret");
    }
}
