package com.sitionix.forgeagent.application.remoteaccess;

import com.sitionix.forgeagent.domain.model.RemoteAccessSwitchState;
import com.sitionix.forgeagent.domain.model.RemoteAccessSwitchStatus;
import com.sitionix.forgeagent.domain.port.RemoteAccessSwitchRepository;

final class RemoteAccessTestSwitch {
    private RemoteAccessTestSwitch() { }

    static RemoteAccessSwitch enabled() {
        return new RemoteAccessSwitch(new RemoteAccessSwitchRepository() {
            @Override public RemoteAccessSwitchState get() {
                return new RemoteAccessSwitchState(RemoteAccessSwitchStatus.ENABLED,0);
            }
            @Override public boolean transition(RemoteAccessSwitchState before, RemoteAccessSwitchState after) {
                throw new UnsupportedOperationException("This fixture only exercises enabled admission");
            }
        });
    }
}
