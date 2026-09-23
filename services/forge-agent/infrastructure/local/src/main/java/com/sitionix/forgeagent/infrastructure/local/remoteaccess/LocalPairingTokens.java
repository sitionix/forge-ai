package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.RemoteAccessPairingTokens;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Typed copyable envelope; OpenSSH validates Ed25519 private material in an owner-only temporary directory. */
@Component
public final class LocalPairingTokens implements RemoteAccessPairingTokens {
    private static final String PREFIX = "fgpair_v1_";
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).build();

    private record Payload(int version, UUID invitationId, UUID grantorInstanceId, String grantorDisplayName,
            String sshHost, int sshPort, String sshUsername, String sshHostPublicKey,
            String pairingPrivateKey, String expiresAt) {
        @Override public String toString() { return "PairingPayload[REDACTED]"; }
    }

    @Override public RemoteAccessPairingKeys generate() {
        try (var temporary = new TemporaryKey()) {
            command("-q", "-t", "ed25519", "-N", "", "-C", "", "-f", temporary.key.toString());
            String publicKey = validatedPublicKey(Files.readString(temporary.publicKey).strip());
            byte[] material = Files.readAllBytes(temporary.key);
            try {
                return new RemoteAccessPairingKeys(publicKey, fingerprint(publicKey), new RemoteAccessPrivateKey(material));
            } finally { Arrays.fill(material, (byte) 0); }
        } catch (Exception failure) { throw new IllegalStateException("Pairing key generation failed"); }
    }

    @Override public void validateEndpoint(RemoteAccessEndpoint endpoint) {
        if (!endpoint.host().matches("[A-Za-z0-9][A-Za-z0-9.:-]{0,253}")
                || endpoint.host().equals("0.0.0.0") || !endpoint.username().equals("forge-ssh")) {
            throw new IllegalArgumentException("Invalid managed SSH endpoint");
        }
    }

    @Override public RemoteAccessPairingToken encode(RemoteAccessInvitation invitation, String displayName,
            String hostPublicKey, RemoteAccessPrivateKey privateKey) {
        validateEndpoint(invitation.endpoint());
        validateName(displayName);
        validatedPublicKey(hostPublicKey);
        byte[] material = privateKey.copyBytes();
        try {
            if (!publicFromPrivate(material).equals(invitation.pairingPublicKey())) throw new IllegalArgumentException("Invalid pairing key");
            var endpoint = invitation.endpoint();
            var payload = new Payload(1, invitation.id(), invitation.grantorInstanceId(), displayName, endpoint.host(),
                    endpoint.port(), endpoint.username(), hostPublicKey, new String(material, StandardCharsets.US_ASCII), invitation.expiresAt().toString());
            return new RemoteAccessPairingToken(PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(payload)));
        } catch (IOException failure) { throw new IllegalStateException("Pairing token encoding failed"); }
        finally { Arrays.fill(material, (byte) 0); }
    }

    @Override public RemoteAccessPairingDetails decode(String token, UUID localInstanceId) {
        RemoteAccessPairingDetails result;
        try {
            if (token == null || token.length() > 8192 || !token.startsWith(PREFIX)) throw new IllegalArgumentException();
            String encoded = token.substring(PREFIX.length());
            if (!encoded.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)) throw new IllegalArgumentException();
            Payload payload;
            try { payload = JSON.readValue(decoded, Payload.class); }
            finally { Arrays.fill(decoded, (byte) 0); }
            if (payload.version != 1 || payload.invitationId == null || payload.grantorInstanceId == null) throw new IllegalArgumentException();
            validateName(payload.grantorDisplayName);
            var endpoint = new RemoteAccessEndpoint(payload.sshHost, payload.sshPort, payload.sshUsername);
            validateEndpoint(endpoint);
            validatedPublicKey(payload.sshHostPublicKey);
            Instant expires = Instant.parse(payload.expiresAt);
            byte[] privateBytes = payload.pairingPrivateKey.getBytes(StandardCharsets.US_ASCII);
            try {
                publicFromPrivate(privateBytes);
                result = new RemoteAccessPairingDetails(payload.invitationId, payload.grantorInstanceId, payload.grantorDisplayName,
                        endpoint, payload.sshHostPublicKey, new RemoteAccessPrivateKey(privateBytes), expires);
            } finally { Arrays.fill(privateBytes, (byte) 0); }
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid pairing token"); }
        if (localInstanceId == null || result.grantorInstanceId().equals(localInstanceId)) {
            result.close();
            throw new IllegalArgumentException("Self pairing is not allowed");
        }
        return result;
    }

    static String validatedPublicKey(String value) {
        try {
            String[] fields = value.split(" ", -1);
            if (fields.length != 2 || !fields[0].equals("ssh-ed25519")) throw new IllegalArgumentException();
            byte[] blob = Base64.getDecoder().decode(fields[1]);
            byte[] prefix = ByteBuffer.allocate(19).putInt(11).put("ssh-ed25519".getBytes(StandardCharsets.US_ASCII)).putInt(32).array();
            if (blob.length != 51 || !Arrays.equals(Arrays.copyOf(blob, 19), prefix)
                    || !Base64.getEncoder().encodeToString(blob).equals(fields[1])) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid Ed25519 public key"); }
    }

    static String fingerprint(String publicKey) {
        try {
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(Base64.getDecoder().decode(validatedPublicKey(publicKey).split(" ")[1])));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 128 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid grantor display name");
        }
    }

    private static String publicFromPrivate(byte[] bytes) {
        if (bytes.length > 4096) throw new IllegalArgumentException("Invalid pairing key");
        try (var temporary = new TemporaryKey()) {
            Files.createFile(temporary.key, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(temporary.key, bytes);
            return validatedPublicKey(command("-y", "-P", "", "-f", temporary.key.toString()).strip());
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid pairing key"); }
    }

    private static String command(String... arguments) throws IOException, InterruptedException {
        var argv = new ArrayList<>(List.of("/usr/bin/ssh-keygen"));
        argv.addAll(List.of(arguments));
        Process process = new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IOException("Key operation failed");
            byte[] output = process.getInputStream().readNBytes(4097);
            if (output.length > 4096) throw new IOException("Key output limit exceeded");
            return new String(output, StandardCharsets.US_ASCII);
        } finally { process.destroyForcibly(); process.getInputStream().close(); }
    }

    private static final class TemporaryKey implements AutoCloseable {
        final Path directory = Files.createTempDirectory("forge-pairing-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final Path key = directory.resolve("key");
        final Path publicKey = directory.resolve("key.pub");
        TemporaryKey() throws IOException { }
        @Override public void close() throws IOException {
            try { Files.deleteIfExists(key); }
            finally { try { Files.deleteIfExists(publicKey); } finally { Files.delete(directory); } }
        }
    }
}
