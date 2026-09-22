package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.RemoteAccessSession;
import java.util.Optional;
import java.util.UUID;

public interface RemoteAccessSessionRepository {
    void insert(RemoteAccessSession session);
    Optional<RemoteAccessSession> findById(UUID id);
    boolean transition(RemoteAccessSession before, RemoteAccessSession after);
}
