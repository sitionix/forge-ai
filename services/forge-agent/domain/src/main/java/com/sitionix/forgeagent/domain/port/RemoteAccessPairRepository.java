package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.RemoteAccessPair;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface RemoteAccessPairRepository {
    void insert(RemoteAccessPair pair);
    Optional<RemoteAccessPair> findById(UUID id);
    Optional<RemoteAccessPair> findBySession(UUID sessionId);
    boolean isReverseInvitation(UUID invitationId);
    List<RemoteAccessPair> findConnectorPairs();
    boolean transition(RemoteAccessPair before,RemoteAccessPair after);
}
