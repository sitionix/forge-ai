package com.sitionix.forgeagent.application.remoteaccess;

import static org.assertj.core.api.Assertions.*;
import com.sitionix.forgeagent.domain.model.*;
import org.junit.jupiter.api.Test;

class RemoteAccessSwitchStateTest {
    @Test void transitionOrderIsExplicitAndVersioned() {
        var disabled = new RemoteAccessSwitchState(RemoteAccessSwitchStatus.DISABLED, 0);
        var enabled = disabled.enable();
        var disabling = enabled.beginDisable();
        var done = disabling.finishDisable();
        assertThat(enabled).isEqualTo(new RemoteAccessSwitchState(RemoteAccessSwitchStatus.ENABLED, 1));
        assertThat(disabling).isEqualTo(new RemoteAccessSwitchState(RemoteAccessSwitchStatus.DISABLING, 2));
        assertThat(done).isEqualTo(new RemoteAccessSwitchState(RemoteAccessSwitchStatus.DISABLED, 3));
        assertThatThrownBy(disabled::beginDisable).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(disabling::enable).isInstanceOf(IllegalStateException.class);
    }
}
