package com.sitionix.forgeagent.domain.port;
import com.sitionix.forgeagent.domain.model.*;
import java.util.UUID;
public interface RemoteAccessPairingTokens {
    RemoteAccessPairingKeys generate();
    void validateEndpoint(RemoteAccessEndpoint endpoint);
    RemoteAccessPairingToken encode(RemoteAccessInvitation invitation, String displayName, String hostPublicKey, RemoteAccessPrivateKey privateKey);
    RemoteAccessPairingDetails decode(String token, UUID localInstanceId);
}
