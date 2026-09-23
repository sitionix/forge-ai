package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.RemoteAccessCredentialStore;
import com.sitionix.forgeagent.domain.port.RemoteAccessPairingTransport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** One fixed SSH operation per call. Private credentials exist only in protected temporary files. */
@Component
public final class LocalRemoteAccessPairingTransport implements RemoteAccessPairingTransport {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RemoteAccessCredentialStore credentials;
    private final ControlProcess process;

    @FunctionalInterface interface ControlProcess {
        String execute(List<String> command, byte[] input) throws Exception;
    }
    @Autowired public LocalRemoteAccessPairingTransport(RemoteAccessCredentialStore credentials) {
        this(credentials,(command,input) -> RemoteAccessControlProcess.execute(command,input,Duration.ofSeconds(15)));
    }
    LocalRemoteAccessPairingTransport(RemoteAccessCredentialStore credentials,ControlProcess process) {
        this.credentials=credentials;
        this.process=process;
    }

    @Override public void redeem(RemoteAccessSession session,RemoteAccessPrivateKey invitationKey,String accessorDisplayName) {
        if (accessorDisplayName==null || accessorDisplayName.isBlank() || accessorDisplayName.length()>128
                || accessorDisplayName.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid accessor name");
        try {
            var request=new RemoteAccessPairingRequest(session.id(),session.accessorInstanceId(),accessorDisplayName,
                    LocalPairingTokens.validatedPublicKey(session.sessionPublicKey()));
            byte[] input=JSON.writeValueAsBytes(request);
            String response=invoke(session,invitationKey,"redeem",input);
            if (!response.equals("PROVISIONING "+session.id()+"\n")) throw new IllegalStateException();
        } catch (Exception failure) { throw unavailable(failure); }
    }
    @Override public RemoteAccessSessionStatus confirm(RemoteAccessSession session) { return sessionOperation(session,"confirm"); }
    @Override public RemoteAccessSessionStatus status(RemoteAccessSession session) { return sessionOperation(session,"status"); }

    private RemoteAccessSessionStatus sessionOperation(RemoteAccessSession session,String operation) {
        try (var privateKey=credentials.read(session.localPrivateKeyReference())) {
            String response=invoke(session,privateKey,operation,new byte[0]);
            if (response.equals("ACTIVE\n")) return RemoteAccessSessionStatus.ACTIVE;
            if (operation.equals("status") && response.equals("PROVISIONING\n")) return RemoteAccessSessionStatus.PROVISIONING;
            throw new IllegalStateException();
        } catch (Exception failure) { throw unavailable(failure); }
    }

    private String invoke(RemoteAccessSession session,RemoteAccessPrivateKey privateKey,String operation,byte[] input) throws Exception {
        try (var temporary=new Credentials()) {
            byte[] material=privateKey.copyBytes();
            try { Files.write(temporary.key,material); }
            finally { Arrays.fill(material,(byte)0); }
            var endpoint=session.endpoint();
            Files.writeString(temporary.knownHosts,"["+endpoint.host()+"]:"+endpoint.port()+" "
                    +LocalPairingTokens.validatedPublicKey(session.pinnedHostPublicKey())+"\n",StandardCharsets.US_ASCII);
            return process.execute(RemoteAccessSshCommand.control(session,temporary.key,temporary.knownHosts,operation),input);
        }
    }
    private static IllegalStateException unavailable(Exception failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        return new IllegalStateException("Remote access control operation unavailable");
    }

    private static final class Credentials implements AutoCloseable {
        final Path directory;
        final Path key;
        final Path knownHosts;
        Credentials() throws IOException {
            directory=Files.createTempDirectory("forge-control-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            key=directory.resolve("identity"); knownHosts=directory.resolve("known_hosts");
            try {
                var mode=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
                Files.createFile(key,mode); Files.createFile(knownHosts,mode);
            } catch (IOException | RuntimeException failure) { close(); throw failure; }
        }
        @Override public void close() throws IOException {
            try { Files.deleteIfExists(key); }
            finally { try { Files.deleteIfExists(knownHosts); } finally { Files.deleteIfExists(directory); } }
        }
    }
}
