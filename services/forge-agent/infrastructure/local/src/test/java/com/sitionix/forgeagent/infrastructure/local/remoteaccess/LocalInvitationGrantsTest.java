package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import com.sitionix.forgeagent.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalInvitationGrantsTest {
    @TempDir Path temp;
    @Test void typedGrantUsesBoundedControlFrameAndRequiresAcknowledgement() throws Exception {
        var invitation = new RemoteAccessInvitation(UUID.randomUUID(),UUID.randomUUID(),new RemoteAccessEndpoint("localhost",2222,"forge-ssh"),
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4f", "fingerprint",
                Instant.now(),Instant.now().plusSeconds(300),null,null,null);
        Path socket=temp.resolve("supervisor.sock");
        try (var server=ServerSocketChannel.open(StandardProtocolFamily.UNIX); var worker=Executors.newSingleThreadExecutor()) {
            server.bind(UnixDomainSocketAddress.of(socket));
            var request=worker.submit(() -> exchange(server,"OK\n"));
            var client=new LocalInvitationGrants(socket,System.getProperty("user.name"));
            client.install(invitation);
            assertThat(request.get(5,TimeUnit.SECONDS)).isEqualTo("INSTALL "+invitation.grantorInstanceId()+" "+invitation.id()+" "+invitation.pairingPublicKey()+"\n");
            var denied=worker.submit(() -> exchange(server,"DENIED\n"));
            assertThatThrownBy(() -> client.remove(invitation)).isInstanceOf(IllegalStateException.class).hasMessage("Invitation supervisor rejected operation");
            assertThat(denied.get(5,TimeUnit.SECONDS)).startsWith("REMOVE ");
        }
    }
    @Test void missingSupervisorFailsWithoutFallback() {
        var client=new LocalInvitationGrants(temp.resolve("absent.sock"),System.getProperty("user.name"));
        assertThatThrownBy(client::hostPublicKey).isInstanceOf(IllegalStateException.class).hasMessage("Invitation supervisor unavailable");
    }
    private static String exchange(ServerSocketChannel server,String response) throws Exception {
        try (var socket=server.accept()) {
            var data=ByteBuffer.allocate(1024);
            while (socket.read(data)>0) if (data.get(data.position()-1)=='\n') break;
            socket.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.US_ASCII)));
            return new String(data.array(),0,data.position(),StandardCharsets.US_ASCII);
        }
    }
}
