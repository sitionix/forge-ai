package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import static org.assertj.core.api.Assertions.*;
import com.sitionix.forgeagent.domain.model.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalPairingTokensTest {
    private final LocalPairingTokens tokens = new LocalPairingTokens();

    @Test void generatedTokenRoundTripsAndSecretRepresentationsAreRedacted() {
        try (var keys = tokens.generate()) {
            var invitation = invitation(keys.publicKey(), keys.fingerprint());
            var token = tokens.encode(invitation, "Grantor", keys.publicKey(), keys.privateKey());
            assertThat(token.value()).startsWith("fgpair_v1_");
            assertThat(token.toString()).doesNotContain(token.value());
            try (var parsed = tokens.decode(token.value(), UUID.randomUUID())) {
                assertThat(parsed.invitationId()).isEqualTo(invitation.id());
                assertThat(parsed.grantorInstanceId()).isEqualTo(invitation.grantorInstanceId());
                assertThat(parsed.endpoint()).isEqualTo(invitation.endpoint());
                assertThat(parsed.hostPublicKey()).isEqualTo(keys.publicKey());
                assertThat(parsed.expiresAt()).isEqualTo(invitation.expiresAt());
                assertThat(parsed.privateKey().copyBytes()).isEqualTo(keys.privateKey().copyBytes());
                assertThat(parsed.toString()).doesNotContain("BEGIN OPENSSH", token.value());
            }
            assertThatThrownBy(() -> tokens.decode(token.value(), invitation.grantorInstanceId()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Self pairing is not allowed");
        }
    }

    @Test void rejectsMalformedEnvelopePayloadAndKeyWithoutSecretInErrors() {
        for (String input : new String[]{"", "fgpair_v2_e30", "fgpair_v1_***", "fgpair_v1_e30", "fgpair_v1_" + "A".repeat(20000)}) {
            assertThatThrownBy(() -> tokens.decode(input, UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid pairing token").hasNoCause();
        }
        try (var keys = tokens.generate()) {
            var invitation = invitation(keys.publicKey(), keys.fingerprint());
            assertThatThrownBy(() -> tokens.encode(invitation, "Grantor", "ssh-ed25519 bad", keys.privateKey()))
                    .isInstanceOf(IllegalArgumentException.class);
            try (var invalid = new RemoteAccessPrivateKey("synthetic-secret-invalid".getBytes())) {
                assertThatThrownBy(() -> tokens.encode(invitation, "Grantor", keys.publicKey(), invalid))
                        .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid pairing key").hasNoCause();
            }
        }
    }

    @Test void typedPayloadRejectsFractionalVersionsPortsAndCoercedScalars() {
        try (var keys = tokens.generate()) {
            var invitation = invitation(keys.publicKey(),keys.fingerprint());
            String encoded = tokens.encode(invitation,"Grantor",keys.publicKey(),keys.privateKey()).value();
            String json = new String(java.util.Base64.getUrlDecoder().decode(encoded.substring("fgpair_v1_".length())),java.nio.charset.StandardCharsets.UTF_8);
            for (String changed : new String[]{json.replace("\"version\":1", "\"version\":1.9"),
                    json.replace("\"sshPort\":2222", "\"sshPort\":2222.9"),
                    json.replace("\"version\":1", "\"version\":\"1\""),
                    json.replace("\"sshPort\":2222", "\"sshPort\":null"),
                    json.replace("\"grantorDisplayName\":\"Grantor\"", "\"grantorDisplayName\":3"),
                    json.replace("\"grantorDisplayName\":\"Grantor\"", "\"grantorDisplayName\":true")}) {
                assertThat(changed).isNotEqualTo(json);
                String malformed="fgpair_v1_"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(changed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                assertThatThrownBy(() -> tokens.decode(malformed,UUID.randomUUID()))
                        .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid pairing token").hasNoCause();
            }
        }
    }

    @Test void endpointCannotSupplySshOptionsCommandsOrWhitespace() {
        for (String host : new String[]{"-oProxyCommand=id", "host\nother", "$(id)", "host;id", "host name", "*", "0.0.0.0", "::"}) {
            assertThatThrownBy(() -> tokens.validateEndpoint(new RemoteAccessEndpoint(host, 2222, "forge-ssh")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatCode(() -> tokens.validateEndpoint(new RemoteAccessEndpoint("192.0.2.20", 2222, "forge-ssh"))).doesNotThrowAnyException();
    }

    private RemoteAccessInvitation invitation(String key, String fingerprint) {
        return new RemoteAccessInvitation(UUID.randomUUID(), UUID.randomUUID(),
                new RemoteAccessEndpoint("192.0.2.20", 2222, "forge-ssh"), key, fingerprint,
                Instant.parse("2026-09-23T12:00:00Z"), Instant.parse("2026-09-23T12:05:00Z"), null, null, null);
    }
}
