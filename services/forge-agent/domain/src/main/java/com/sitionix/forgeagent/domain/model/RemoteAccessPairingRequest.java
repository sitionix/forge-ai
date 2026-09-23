package com.sitionix.forgeagent.domain.model;
import java.util.UUID;
public record RemoteAccessPairingRequest(UUID sessionId, UUID accessorInstanceId, String accessorDisplayName, String sessionPublicKey) { }
