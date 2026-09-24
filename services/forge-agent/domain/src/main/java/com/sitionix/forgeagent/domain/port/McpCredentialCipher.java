package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.McpEncryptedCredential;
import java.util.UUID;

public interface McpCredentialCipher {
    McpEncryptedCredential encrypt(UUID installationId, UUID connectionId, String purpose, byte[] plaintext);
    byte[] decrypt(UUID installationId, UUID connectionId, String purpose, McpEncryptedCredential encrypted);
}
