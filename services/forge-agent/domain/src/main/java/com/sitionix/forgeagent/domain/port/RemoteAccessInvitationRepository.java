package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.RemoteAccessInvitation;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RemoteAccessInvitationRepository {
    void insert(RemoteAccessInvitation invitation);
    Optional<RemoteAccessInvitation> findById(UUID id);
    boolean reserve(UUID invitationId, UUID sessionId, Instant now);
    boolean cancel(UUID invitationId, Instant now);
}
