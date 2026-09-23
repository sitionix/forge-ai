package com.sitionix.forgeagent.domain.port;
import com.sitionix.forgeagent.domain.model.RemoteAccessSession;
import com.sitionix.forgeagent.domain.model.RemoteAccessSessionStatus;
public interface RemoteAccessRevokeTransport {
    RemoteAccessSessionStatus revoke(RemoteAccessSession accessor);
}
