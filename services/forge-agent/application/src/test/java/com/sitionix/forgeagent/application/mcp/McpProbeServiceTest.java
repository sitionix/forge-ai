package com.sitionix.forgeagent.application.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sitionix.forgeagent.domain.exception.McpProbeException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpProbeServiceTest {
    private final UUID installation = UUID.randomUUID(), id = UUID.randomUUID();
    private final McpConnection connection = new McpConnection(id, installation, "test", URI.create("https://example.org/mcp"),
            McpAuthType.BEARER, false, McpProjectAccess.all(), Set.of(), true,
            Instant.now(), Instant.now(), null, null);
    private final McpConnectionRepository repository = mock(McpConnectionRepository.class);
    private final McpCredentialCipher cipher = mock(McpCredentialCipher.class);
    private final McpRemoteProbe remote = mock(McpRemoteProbe.class);
    private final McpToolInventoryRepository inventory = mock(McpToolInventoryRepository.class);
    private final McpProbeService service = new McpProbeService(repository, () -> installation, cipher, remote, inventory);

    @Test void decryptsOnlyForProbeAndClearsPlaintext() {
        var encrypted = new McpEncryptedCredential("test", new byte[]{1});
        byte[] plaintext = "synthetic-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var report = new McpProbeReport("2025-06-18", List.of(new McpToolSummary("read", "Read", "sha256:abc")));
        when(repository.findById(installation, id)).thenReturn(Optional.of(connection));
        when(repository.credential(installation, id)).thenReturn(Optional.of(encrypted));
        when(cipher.decrypt(installation, id, "credential", encrypted)).thenReturn(plaintext);
        when(remote.probe(connection.endpoint(), connection.authType(), plaintext)).thenReturn(report);

        assertThat(service.test(id)).isEqualTo(report);
        verify(remote).probe(connection.endpoint(), connection.authType(), plaintext);
        verify(inventory).replace(installation, id, connection.endpoint(), connection.authType(), encrypted, report.tools());
        assertThat(plaintext).containsOnly((byte) 0);
    }

    @Test void missingConfiguredCredentialNeverCallsRemote() {
        when(repository.findById(installation, id)).thenReturn(Optional.of(connection));
        when(repository.credential(installation, id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.test(id)).isInstanceOf(McpProbeException.class)
                .extracting("reason").isEqualTo(McpProbeException.Reason.AUTH_REQUIRED);
        verifyNoInteractions(remote);
        verifyNoInteractions(inventory);
    }

    @Test void clearsPlaintextWhenRemoteFailsAndNeverStoresInventory() {
        byte[] plaintext = "synthetic-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var encrypted = new McpEncryptedCredential("test", new byte[]{1});
        when(repository.findById(installation, id)).thenReturn(Optional.of(connection));
        when(repository.credential(installation, id)).thenReturn(Optional.of(encrypted));
        when(cipher.decrypt(installation, id, "credential", encrypted)).thenReturn(plaintext);
        when(remote.probe(connection.endpoint(), connection.authType(), plaintext))
                .thenThrow(new McpProbeException(McpProbeException.Reason.UNAVAILABLE));
        assertThatThrownBy(() -> service.test(id)).isInstanceOf(McpProbeException.class);
        assertThat(plaintext).containsOnly((byte) 0);
        verifyNoInteractions(inventory);
    }
}
