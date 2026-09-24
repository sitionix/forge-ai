package com.sitionix.forgeagent.domain.model;

import java.util.Objects;

/** Local feature admission state; individual session statuses remain authoritative. */
public record RemoteAccessSwitchState(RemoteAccessSwitchStatus status, long version) {
    public RemoteAccessSwitchState {
        Objects.requireNonNull(status);
        if (version < 0) throw new IllegalArgumentException("Negative switch version");
    }

    public RemoteAccessSwitchState enable() {
        if (status != RemoteAccessSwitchStatus.DISABLED) throw new IllegalStateException("Switch is not disabled");
        return new RemoteAccessSwitchState(RemoteAccessSwitchStatus.ENABLED, version + 1);
    }

    public RemoteAccessSwitchState beginDisable() {
        if (status != RemoteAccessSwitchStatus.ENABLED) throw new IllegalStateException("Switch is not enabled");
        return new RemoteAccessSwitchState(RemoteAccessSwitchStatus.DISABLING, version + 1);
    }

    public RemoteAccessSwitchState finishDisable() {
        if (status != RemoteAccessSwitchStatus.DISABLING) throw new IllegalStateException("Switch is not disabling");
        return new RemoteAccessSwitchState(RemoteAccessSwitchStatus.DISABLED, version + 1);
    }
}
