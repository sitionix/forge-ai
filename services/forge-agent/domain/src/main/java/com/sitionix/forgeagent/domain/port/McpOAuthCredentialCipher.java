package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.*;
import java.util.UUID;

public interface McpOAuthCredentialCipher {
    McpEncryptedCredential encrypt(UUID owner, UUID connection, McpOAuthCredentials credentials);
    McpOAuthCredentials decrypt(UUID owner, UUID connection, McpEncryptedCredential encrypted);
}
