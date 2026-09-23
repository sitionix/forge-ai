package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.RemoteAccessKeyBinding;
import com.sitionix.forgeagent.domain.model.RemoteAccessSessionStatus;
import java.util.UUID;

public interface RemoteAccessPeerExecution {
    void start(RemoteAccessKeyBinding binding, UUID attachmentId);
    RemoteAccessSessionStatus revoke(RemoteAccessKeyBinding binding);
}
