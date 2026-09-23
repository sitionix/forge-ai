package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.RemoteAccessKeyBinding;
import com.sitionix.forgeagent.domain.model.RemoteAccessInvitationBinding;
import com.sitionix.forgeagent.domain.model.RemoteAccessSessionStatus;
import java.util.Optional;

public interface RemoteAccessChannelAuthority {
    Optional<RemoteAccessSessionStatus> sessionStatus(RemoteAccessKeyBinding binding);
    default boolean pairingAllowed(RemoteAccessInvitationBinding binding) { return false; }
}
