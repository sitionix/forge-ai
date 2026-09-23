package com.sitionix.forgeagent.domain.port;
import com.sitionix.forgeagent.domain.model.*;
public interface RemoteAccessPairingTransport {
    void redeem(RemoteAccessSession accessor, RemoteAccessPrivateKey invitationKey, String accessorDisplayName);
    RemoteAccessSessionStatus confirm(RemoteAccessSession accessor);
    RemoteAccessSessionStatus status(RemoteAccessSession accessor);
}
