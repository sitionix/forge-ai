package com.sitionix.forgeagent.application.remoteaccess;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.RemoteAccessSetup;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RemoteAccessControlServiceTest {
    @Test void confirmedCleanupAloneAllowsDisabled() {
        var access=mock(RemoteAccessSwitch.class);
        var invitations=mock(RemoteAccessInvitations.class);
        var management=mock(RemoteAccessManagement.class);
        var setup=mock(RemoteAccessSetup.class);
        var active=RemoteAccessExecutionServiceTest.session().activate(RemoteAccessExecutionServiceTest.NOW);
        var revoked=active.requestRevoke(RemoteAccessExecutionServiceTest.NOW)
                .confirmRevoked(RemoteAccessExecutionServiceTest.NOW);
        var id=active.id();
        when(access.beginDisable()).thenReturn(new RemoteAccessSwitchState(RemoteAccessSwitchStatus.DISABLING,1));
        when(access.finishDisable()).thenReturn(new RemoteAccessSwitchState(RemoteAccessSwitchStatus.DISABLED,2));
        when(access.status()).thenReturn(new RemoteAccessSwitchState(RemoteAccessSwitchStatus.DISABLING,1));
        when(setup.capabilities()).thenReturn(new RemoteAccessCapabilities(true,List.of(),List.of()));
        when(management.list()).thenReturn(List.of(active),List.of(active),List.of(revoked));

        var sut=new RemoteAccessControlService(access,invitations,management,setup);
        var result=sut.disable();

        assertThat(result.status()).isEqualTo(RemoteAccessSwitchStatus.DISABLING);
        verify(management,never()).revoke(id);
        sut.reconcile();
        verify(management).revoke(id);
        verify(access).finishDisable();
    }

    @Test void offlinePeerKeepsSwitchDisablingAndRetryable() {
        var access=mock(RemoteAccessSwitch.class);
        var invitations=mock(RemoteAccessInvitations.class);
        var management=mock(RemoteAccessManagement.class);
        var setup=mock(RemoteAccessSetup.class);
        var revoking=RemoteAccessExecutionServiceTest.session().activate(RemoteAccessExecutionServiceTest.NOW)
                .requestRevoke(RemoteAccessExecutionServiceTest.NOW);
        when(access.beginDisable()).thenReturn(new RemoteAccessSwitchState(RemoteAccessSwitchStatus.DISABLING,1));
        when(access.status()).thenReturn(new RemoteAccessSwitchState(RemoteAccessSwitchStatus.DISABLING,1));
        when(setup.capabilities()).thenReturn(new RemoteAccessCapabilities(true,List.of(),List.of()));
        when(management.list()).thenReturn(List.of(revoking));

        var sut=new RemoteAccessControlService(access,invitations,management,setup);
        var result=sut.disable();
        sut.reconcile();

        assertThat(result.status()).isEqualTo(RemoteAccessSwitchStatus.DISABLING);
        assertThat(result.pendingSessions()).isEqualTo(1);
        verify(access,never()).finishDisable();
    }
}
