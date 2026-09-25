package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.RemoteAccessSwitchState;

public interface RemoteAccessSwitchRepository {
    RemoteAccessSwitchState get();
    boolean transition(RemoteAccessSwitchState before, RemoteAccessSwitchState after);
}
