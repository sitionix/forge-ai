package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.RemoteAccessPairingToken;
import java.util.UUID;

/** Protected, local retry copy of the short-lived internal reverse invitation. */
public interface RemoteAccessReverseInvitationStore {
    void store(UUID pairId, RemoteAccessPairingToken token);
    RemoteAccessPairingToken read(UUID pairId);
    void delete(UUID pairId);
}
