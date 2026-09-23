package com.sitionix.forgeagent.domain.port;
import com.sitionix.forgeagent.domain.model.RemoteAccessSession;
public interface RemoteAccessSessionGrants {
    void install(RemoteAccessSession session);
    void remove(RemoteAccessSession session);
}
