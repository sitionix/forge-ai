package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.RemoteAccessSession;
import java.util.Optional;
import java.util.UUID;

public interface RemoteAccessSessionRepository {
    void insert(RemoteAccessSession session);
    Optional<RemoteAccessSession> findById(UUID id);
    Optional<RemoteAccessSession> findByInvitation(UUID invitationId);
    java.util.List<RemoteAccessSession> findLocal(UUID instanceId);
    boolean recordFailure(RemoteAccessSession before, String code, String message);
    boolean transition(RemoteAccessSession before, RemoteAccessSession after);
}
