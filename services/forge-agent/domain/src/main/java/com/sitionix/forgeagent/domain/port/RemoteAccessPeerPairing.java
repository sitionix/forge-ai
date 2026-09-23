package com.sitionix.forgeagent.domain.port;
import com.sitionix.forgeagent.domain.model.*;
import java.util.Optional;
import java.util.UUID;
public interface RemoteAccessPeerPairing {
    UUID redeem(RemoteAccessInvitationBinding binding, RemoteAccessPairingRequest request);
    Optional<RemoteAccessSessionStatus> confirm(RemoteAccessKeyBinding binding);
}
