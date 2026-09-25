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
public final class LocalRemoteAccessPairingTransport implements RemoteAccessPairingTransport, com.sitionix.forgeagent.domain.port.RemoteAccessRevokeTransport, com.sitionix.forgeagent.domain.port.RemoteAccessReverseExchange {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RemoteAccessCredentialStore credentials;
    private final ControlProcess process;

    @FunctionalInterface interface ControlProcess {
        String execute(List<String> command, byte[] input) throws Exception;
    }
    @Autowired public LocalRemoteAccessPairingTransport(RemoteAccessCredentialStore credentials) {
        this(credentials,(command,input) -> RemoteAccessControlProcess.execute(command,input,Duration.ofSeconds(List.of("revoke","reverse").contains(command.getLast()) ? 90 : 15)));
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

    @Override public RemoteAccessSessionStatus revoke(RemoteAccessSession session) { return sessionOperation(session,"revoke"); }

    @Override public java.util.UUID exchange(RemoteAccessSession forwardSession,String reverseToken) {
        try (var privateKey=credentials.read(forwardSession.localPrivateKeyReference())) {
            var request=new RemoteAccessReverseRequest(forwardSession.invitationId(),reverseToken);
            String response=invoke(forwardSession,privateKey,"reverse",JSON.writeValueAsBytes(request));
            var match=java.util.regex.Pattern.compile("ACTIVE ([0-9a-f-]{36})\\n").matcher(response);
            if (!match.matches()) throw new IllegalStateException("Reverse pairing was not confirmed");
            var id=java.util.UUID.fromString(match.group(1));
            if (!id.toString().equals(match.group(1))) throw new IllegalStateException("Invalid reverse session identity");
            return id;
        } catch (Exception failure) { throw unavailable(failure); }
    }

    private RemoteAccessSessionStatus sessionOperation(RemoteAccessSession session,String operation) {
        try (var privateKey=credentials.read(session.localPrivateKeyReference())) {
            String response=invoke(session,privateKey,operation,new byte[0]);
            if (operation.equals("revoke")) {
                if (response.equals("REVOKING\n")) return RemoteAccessSessionStatus.REVOKING;
                if (response.equals("REVOKED\n")) return RemoteAccessSessionStatus.REVOKED;
                throw new IllegalStateException();
            }
            if (response.equals("ACTIVE\n")) return RemoteAccessSessionStatus.ACTIVE;
            if (operation.equals("status") && response.equals("PROVISIONING\n")) return RemoteAccessSessionStatus.PROVISIONING;
            throw new IllegalStateException();
        } catch (Exception failure) { throw unavailable(failure); }
    }

    private String invoke(RemoteAccessSession session,RemoteAccessPrivateKey privateKey,String operation,byte[] input) throws Exception {
        try (var temporary=new RemoteAccessSshCredentials(session,privateKey)) {
            return process.execute(RemoteAccessSshCommand.control(session,temporary.key,temporary.knownHosts,operation),input);
        }
    }
    private static IllegalStateException unavailable(Exception failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        return new IllegalStateException("Remote access control operation unavailable");
    }

}
