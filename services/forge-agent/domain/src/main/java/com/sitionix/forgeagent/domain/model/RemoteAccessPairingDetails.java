package com.sitionix.forgeagent.domain.model;
import java.time.Instant;
import java.util.UUID;
public record RemoteAccessPairingDetails(UUID invitationId, UUID grantorInstanceId, String displayName,
        RemoteAccessEndpoint endpoint, String hostPublicKey, RemoteAccessPrivateKey privateKey, Instant expiresAt) implements AutoCloseable {
    @Override public void close() { privateKey.close(); }
    @Override public String toString() { return "RemoteAccessPairingDetails[REDACTED]"; }
}
