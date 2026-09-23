package com.sitionix.forgeagent.application.remoteaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.ForgeInstanceIdentityRepository;
import com.sitionix.forgeagent.domain.port.RemoteAccessSessionRepository;
import com.sitionix.forgeagent.domain.port.RemoteAccessInvitationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RemoteAccessChannelServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    private static final String KEY = "SHA256:" + "A".repeat(43);
    private final UUID local = UUID.randomUUID();
    @Mock private RemoteAccessSessionRepository sessions;
    @Mock private RemoteAccessInvitationRepository invitations;
    @Mock private ForgeInstanceIdentityRepository identity;

    @Test void provisioningAndActiveCanReadOnlyTheirOwnCurrentStatus() {
        var session = session(local, RemoteAccessRole.GRANTOR);
        var sut = service();
        when(identity.getOrCreate()).thenReturn(local);
        when(sessions.findById(session.id())).thenReturn(Optional.of(session), Optional.of(session.activate(NOW)));
        assertThat(sut.sessionStatus(binding(session))).contains(RemoteAccessSessionStatus.PROVISIONING);
        assertThat(sut.sessionStatus(binding(session))).contains(RemoteAccessSessionStatus.ACTIVE);
    }

    @Test void staleAuthorizationIsDeniedAfterRevokeWithoutRestart() {
        var session = session(local, RemoteAccessRole.GRANTOR).activate(NOW);
        var revoking = session.requestRevoke(NOW);
        when(identity.getOrCreate()).thenReturn(local);
        when(sessions.findById(session.id())).thenReturn(Optional.of(session), Optional.of(revoking), Optional.of(revoking.confirmRevoked(NOW)));
        var sut = service();
        assertThat(sut.sessionStatus(binding(session))).contains(RemoteAccessSessionStatus.ACTIVE);
        assertThat(sut.sessionStatus(binding(session))).isEmpty();
        assertThat(sut.sessionStatus(binding(session))).isEmpty();
    }

    @Test void missingSessionIsDenied() {
        when(identity.getOrCreate()).thenReturn(local);
        var binding = new RemoteAccessKeyBinding(local, UUID.randomUUID(), KEY);
        when(sessions.findById(binding.sessionId())).thenReturn(Optional.empty());
        assertThat(service().sessionStatus(binding)).isEmpty();
    }

    @Test void foreignGrantorBindingIsDenied() {
        when(identity.getOrCreate()).thenReturn(local);
        assertThat(service().sessionStatus(new RemoteAccessKeyBinding(UUID.randomUUID(),UUID.randomUUID(),KEY))).isEmpty();
    }

    @Test void foreignStoredGrantorIsDenied() {
        var session = session(UUID.randomUUID(),RemoteAccessRole.GRANTOR);
        when(identity.getOrCreate()).thenReturn(local);
        when(sessions.findById(session.id())).thenReturn(Optional.of(session));
        assertThat(service().sessionStatus(new RemoteAccessKeyBinding(local,session.id(),KEY))).isEmpty();
    }

    @Test void anotherKeyCannotUseTheSession() {
        var session = session(local,RemoteAccessRole.GRANTOR);
        when(identity.getOrCreate()).thenReturn(local);
        when(sessions.findById(session.id())).thenReturn(Optional.of(session));
        assertThat(service().sessionStatus(new RemoteAccessKeyBinding(local,session.id(),"SHA256:"+"B".repeat(43)))).isEmpty();
    }

    @Test void accessorRecordCannotAuthorizeIncomingChannels() {
        var session = session(local,RemoteAccessRole.ACCESSOR);
        when(identity.getOrCreate()).thenReturn(local);
        when(sessions.findById(session.id())).thenReturn(Optional.of(session));
        assertThat(service().sessionStatus(binding(session))).isEmpty();
    }

    @Test void provisioningDeadlineIsExclusive() {
        var session = session(local,RemoteAccessRole.GRANTOR);
        when(identity.getOrCreate()).thenReturn(local);
        when(sessions.findById(session.id())).thenReturn(Optional.of(session));
        var sut = new RemoteAccessChannelService(sessions,identity,Clock.fixed(session.provisioningExpiresAt(),ZoneOffset.UTC),invitations);
        assertThat(sut.sessionStatus(binding(session))).isEmpty();
    }

    private RemoteAccessChannelService service() {
        return new RemoteAccessChannelService(sessions,identity,Clock.fixed(NOW,ZoneOffset.UTC),invitations);
    }
    private RemoteAccessKeyBinding binding(RemoteAccessSession session) {
        return new RemoteAccessKeyBinding(session.grantorInstanceId(),session.id(),KEY);
    }
    private RemoteAccessSession session(UUID grantor,RemoteAccessRole role) {
        UUID id = UUID.randomUUID();
        return new RemoteAccessSession(id,UUID.randomUUID(),role,grantor,UUID.randomUUID(),"peer",
                new RemoteAccessEndpoint("localhost",2222,"forge-ssh"),"host-public","session-public",KEY,
                role==RemoteAccessRole.ACCESSOR?id:null,RemoteAccessSessionStatus.PROVISIONING,NOW.minusSeconds(1),
                NOW.plusSeconds(60),null,null,null,RemoteAccessConnectivity.UNKNOWN,null,null,null,null,0);
    }
}
