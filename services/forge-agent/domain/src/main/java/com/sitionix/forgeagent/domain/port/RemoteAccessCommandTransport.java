package com.sitionix.forgeagent.domain.port;
import com.sitionix.forgeagent.domain.model.RemoteAccessCommand;
import com.sitionix.forgeagent.domain.model.RemoteAccessSession;
public interface RemoteAccessCommandTransport {
    RemoteAccessCommandExecution start(RemoteAccessSession session,RemoteAccessCommand command);
}
