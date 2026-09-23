package com.sitionix.forgeagent.application.remoteaccess;

import com.sitionix.forgeagent.domain.model.RemoteAccessKeyBinding;
import com.sitionix.forgeagent.domain.model.RemoteAccessRole;
import com.sitionix.forgeagent.domain.model.RemoteAccessSessionStatus;
import com.sitionix.forgeagent.domain.port.ForgeInstanceIdentityRepository;
import com.sitionix.forgeagent.domain.port.RemoteAccessChannelAuthority;
import com.sitionix.forgeagent.domain.port.RemoteAccessSessionRepository;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RemoteAccessChannelService implements RemoteAccessChannelAuthority {
    private final RemoteAccessSessionRepository sessions;
    private final ForgeInstanceIdentityRepository identity;
    private final Clock clock;

    @Override
    public Optional<RemoteAccessSessionStatus> sessionStatus(RemoteAccessKeyBinding binding) {
        if (!identity.getOrCreate().equals(binding.grantorInstanceId())) {
            return Optional.empty();
        }
        return sessions.findById(binding.sessionId())
                .filter(session -> session.localRole() == RemoteAccessRole.GRANTOR)
                .filter(session -> session.grantorInstanceId().equals(binding.grantorInstanceId()))
                .filter(session -> session.sessionFingerprint().equals(binding.fingerprint()))
                .filter(session -> session.status() == RemoteAccessSessionStatus.ACTIVE
                        || session.status() == RemoteAccessSessionStatus.PROVISIONING
                        && clock.instant().isBefore(session.provisioningExpiresAt()))
                .map(session -> session.status());
    }
}
