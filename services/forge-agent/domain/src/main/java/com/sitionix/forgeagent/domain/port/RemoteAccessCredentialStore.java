package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.RemoteAccessPrivateKey;
import java.util.UUID;

public interface RemoteAccessCredentialStore {

    UUID store(UUID sessionId, RemoteAccessPrivateKey privateKey);

    RemoteAccessPrivateKey read(UUID reference);

    void delete(UUID reference);
}
