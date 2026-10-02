package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Private, owner-scoped authorization attempt; never a management response. */
public record McpOAuthTransaction(UUID id, UUID installationId, UUID connectionId, String stateHash,
                                  String browserHash, McpConnection snapshot, McpEncryptedCredential verifier,
                                  Instant expiresAt, Instant claimedAt) {
    public McpOAuthTransaction {
        if (id == null || installationId == null || connectionId == null || snapshot == null || verifier == null || expiresAt == null
                || stateHash == null || !stateHash.matches("[0-9a-f]{64}") || browserHash == null || !browserHash.matches("[0-9a-f]{64}")
                || !installationId.equals(snapshot.installationId()) || !connectionId.equals(snapshot.id()) || snapshot.authType() != McpAuthType.OAUTH)
            throw new IllegalArgumentException("Invalid OAuth transaction");
    }
    @Override public String toString() { return "McpOAuthTransaction[redacted]"; }
}
