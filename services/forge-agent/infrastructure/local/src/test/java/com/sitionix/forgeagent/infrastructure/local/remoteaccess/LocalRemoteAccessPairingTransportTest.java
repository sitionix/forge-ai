package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.RemoteAccessCredentialStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class LocalRemoteAccessPairingTransportTest {
    static final String KEY="ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4f";
    static RemoteAccessSession session(RemoteAccessRole role) {
        UUID id=UUID.randomUUID(); Instant now=Instant.now();
        return new RemoteAccessSession(id,UUID.randomUUID(),role,UUID.randomUUID(),UUID.randomUUID(),"Grantor name",
                new RemoteAccessEndpoint("localhost",2222,"forge-ssh"),KEY,KEY,"SHA256:"+"A".repeat(43),
                role==RemoteAccessRole.ACCESSOR?id:null,RemoteAccessSessionStatus.PROVISIONING,now,now.plusSeconds(120),
                null,null,null,RemoteAccessConnectivity.UNKNOWN,null,null,null,null,0);
    }
    @Test void revokeReturnsOnlyConfirmedRemoteLifecycleAndAllowsLocalRevoking() {
        var session=session(RemoteAccessRole.ACCESSOR).requestRevoke(Instant.now());
        for (String response:List.of("REVOKED\n","REVOKING\n")) {
            var transport=new LocalRemoteAccessPairingTransport(store(new AtomicInteger()),(argv,input) -> {
                assertThat(argv.getLast()).isEqualTo("revoke");return response;
            });
            assertThat(transport.revoke(session).name()+"\n").isEqualTo(response);
        }
        var transport=new LocalRemoteAccessPairingTransport(store(new AtomicInteger()),(argv,input) -> "DENIED\n");
        assertThatThrownBy(() -> transport.revoke(session)).hasMessage("Remote access control operation unavailable");
    }
    @Test void redeemUsesInvitationIdentityAndPublicAccessorMetadataThenSessionIdentityForProof() throws Exception {
        var session=session(RemoteAccessRole.ACCESSOR);
        var reads=new AtomicInteger(); var paths=new ArrayList<Path>(); var commands=new ArrayList<String>();
        var transport=new LocalRemoteAccessPairingTransport(store(reads),(argv,input) -> {
            String operation=argv.getLast(); commands.add(operation);
            Path identity=Path.of(argv.get(argv.indexOf("-i")+1)); paths.add(identity.getParent());
            Path known=Path.of(argv.stream().filter(v -> v.startsWith("UserKnownHostsFile=")).findFirst().orElseThrow().substring(19));
            assertThat(Files.getPosixFilePermissions(identity)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            assertThat(Files.getPosixFilePermissions(identity.getParent())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.readString(known)).isEqualTo("[localhost]:2222 "+KEY+"\n");
            assertThat(argv).contains("-F","/dev/null","IdentityAgent=none","StrictHostKeyChecking=yes");
            if(operation.equals("redeem")) {
                assertThat(Files.readString(identity)).isEqualTo("invitation-private");
                var request=JsonMapper.builder().build().readValue(input,RemoteAccessPairingRequest.class);
                assertThat(request).isEqualTo(new RemoteAccessPairingRequest(session.id(),session.accessorInstanceId(),"Accessor name",KEY));
                return "PROVISIONING "+session.id()+"\n";
            }
            assertThat(Files.readString(identity)).isEqualTo("session-private");
            assertThat(input).isEmpty();
            return "ACTIVE\n";
        });
        try(var invitation=new RemoteAccessPrivateKey("invitation-private".getBytes(StandardCharsets.US_ASCII))) {
            transport.redeem(session,invitation,"Accessor name");
            assertThat(reads.get()).isZero();
            assertThat(transport.confirm(session)).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
            assertThat(transport.status(session)).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
            assertThat(invitation.copyBytes()).isEqualTo("invitation-private".getBytes(StandardCharsets.US_ASCII));
        }
        assertThat(reads.get()).isEqualTo(2);
        assertThat(commands).containsExactly("redeem","confirm","status");
        for(Path path:paths) assertThat(path).doesNotExist();
    }
    @Test void invalidResponsesNeverActivateOrRetryAndAllPathsRemoveCredentials() {
        var session=session(RemoteAccessRole.ACCESSOR);
        for(String response:List.of("ACTIVE\n","PROVISIONING "+UUID.randomUUID()+"\n","PROVISIONING "+session.id()+"\nextra","DENIED\n")) {
            var calls=new AtomicInteger(); var paths=new ArrayList<Path>();
            var transport=new LocalRemoteAccessPairingTransport(store(new AtomicInteger()),(argv,input) -> {
                calls.incrementAndGet(); paths.add(Path.of(argv.get(argv.indexOf("-i")+1)).getParent()); return response;
            });
            try(var invitation=new RemoteAccessPrivateKey(new byte[]{1})) {
                assertThatThrownBy(() -> transport.redeem(session,invitation,"Accessor")).hasMessage("Remote access control operation unavailable");
            }
            assertThat(calls.get()).isEqualTo(1);
            for(Path path:paths) assertThat(path).doesNotExist();
        }
        for(String response:List.of("PROVISIONING\n","ACTIVE","ACTIVE\nextra","REVOKED\n","DENIED\n")) {
            var transport=new LocalRemoteAccessPairingTransport(store(new AtomicInteger()),(argv,input) -> response);
            assertThatThrownBy(() -> transport.confirm(session)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void processFailureDoesNotLeakDiagnosticsAndPreservesCancellation() {
        var paths=new ArrayList<Path>();
        var transport=new LocalRemoteAccessPairingTransport(store(new AtomicInteger()),(argv,input) -> {
            paths.add(Path.of(argv.get(argv.indexOf("-i")+1)).getParent()); throw new InterruptedException("secret diagnostic");
        });
        try {
            assertThatThrownBy(() -> transport.confirm(session(RemoteAccessRole.ACCESSOR)))
                    .hasMessage("Remote access control operation unavailable").hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            for(Path path:paths) assertThat(path).doesNotExist();
        } finally { Thread.interrupted(); }
    }
    private static RemoteAccessCredentialStore store(AtomicInteger reads) {
        return new RemoteAccessCredentialStore() {
            public UUID store(UUID id,RemoteAccessPrivateKey key) { throw new AssertionError(); }
            public RemoteAccessPrivateKey read(UUID reference) { reads.incrementAndGet(); return new RemoteAccessPrivateKey("session-private".getBytes(StandardCharsets.US_ASCII)); }
            public void delete(UUID reference) { throw new AssertionError(); }
        };
    }
}
