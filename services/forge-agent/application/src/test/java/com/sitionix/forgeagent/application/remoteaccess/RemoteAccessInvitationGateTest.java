package com.sitionix.forgeagent.application.remoteaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RemoteAccessInvitationGateTest {
    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");
    private static final UUID LOCAL = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();
    private static final String FINGERPRINT = "SHA256:" + "A".repeat(43);
    @Mock RemoteAccessSessionRepository sessions;
    @Mock RemoteAccessInvitationRepository invitations;
    @Mock ForgeInstanceIdentityRepository identity;

    @Test void onlyUnconsumedUncancelledUnexpiredInvitationAllowsPairing() {
        when(identity.getOrCreate()).thenReturn(LOCAL);
        var invitation = invitation(LOCAL);
        var binding = new RemoteAccessInvitationBinding(LOCAL, ID, FINGERPRINT);
        var service = service(NOW);
        when(invitations.findById(ID)).thenReturn(Optional.of(invitation));
        assertThat(service.pairingAllowed(binding)).isTrue();
        assertThat(service(invitation.expiresAt()).pairingAllowed(binding)).isFalse();
        when(invitations.findById(ID)).thenReturn(Optional.of(invitation.cancel(NOW)));
        assertThat(service.pairingAllowed(binding)).isFalse();
        when(invitations.findById(ID)).thenReturn(Optional.of(invitation.redeem(UUID.randomUUID(), NOW)));
        assertThat(service.pairingAllowed(binding)).isFalse();
        when(invitations.findById(ID)).thenReturn(Optional.empty());
        assertThat(service.pairingAllowed(binding)).isFalse();
    }

    @Test void wrongKeyAndForeignGrantorCannotPair() {
        when(identity.getOrCreate()).thenReturn(LOCAL);
        var service = service(NOW);
        assertThat(service.pairingAllowed(new RemoteAccessInvitationBinding(UUID.randomUUID(), ID, FINGERPRINT))).isFalse();
        when(invitations.findById(ID)).thenReturn(Optional.of(invitation(LOCAL)));
        assertThat(service.pairingAllowed(new RemoteAccessInvitationBinding(LOCAL, ID, "SHA256:" + "B".repeat(43)))).isFalse();
        when(invitations.findById(ID)).thenReturn(Optional.of(invitation(UUID.randomUUID())));
        assertThat(service.pairingAllowed(new RemoteAccessInvitationBinding(LOCAL, ID, FINGERPRINT))).isFalse();
    }

    private RemoteAccessChannelService service(Instant now) {
        return new RemoteAccessChannelService(sessions, identity, Clock.fixed(now, ZoneOffset.UTC), invitations);
    }
    private RemoteAccessInvitation invitation(UUID grantor) {
        return new RemoteAccessInvitation(ID, grantor, new RemoteAccessEndpoint("192.0.2.10", 2222, "forge-ssh"),
                "public", FINGERPRINT, NOW.minusSeconds(1), NOW.plusSeconds(299), null, null, null);
    }
}
