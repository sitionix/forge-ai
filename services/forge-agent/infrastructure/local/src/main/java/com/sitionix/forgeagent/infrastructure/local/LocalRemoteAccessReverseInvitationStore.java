package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.model.RemoteAccessPairingToken;
import com.sitionix.forgeagent.domain.model.RemoteAccessPrivateKey;
import com.sitionix.forgeagent.domain.port.RemoteAccessReverseInvitationStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Uses the existing owner-only credential file semantics in a separate directory. */
@Component
public final class LocalRemoteAccessReverseInvitationStore implements RemoteAccessReverseInvitationStore {
    private final LocalRemoteAccessCredentialStore protectedFiles;

    public LocalRemoteAccessReverseInvitationStore(
            @Value("${forge.agent.remote-access.credential-directory:${FORGE_RUNTIME_DIR:./var}/agent/remote-access/credentials}") String credentialDirectory) {
        this.protectedFiles=new LocalRemoteAccessCredentialStore(Path.of(credentialDirectory).resolveSibling("reverse-invitations"));
    }

    @Override public void store(UUID pairId,RemoteAccessPairingToken token) {
        byte[] bytes=token.value().getBytes(StandardCharsets.US_ASCII);
        try (var secret=new RemoteAccessPrivateKey(bytes)) { protectedFiles.store(pairId,secret); }
        finally { Arrays.fill(bytes,(byte)0); }
    }

    @Override public RemoteAccessPairingToken read(UUID pairId) {
        try (var secret=protectedFiles.read(pairId)) {
            byte[] bytes=secret.copyBytes();
            try { return new RemoteAccessPairingToken(new String(bytes,StandardCharsets.US_ASCII)); }
            finally { Arrays.fill(bytes,(byte)0); }
        }
    }

    @Override public void delete(UUID pairId) { protectedFiles.delete(pairId); }
}
