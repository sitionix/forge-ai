package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import com.sitionix.forgeagent.domain.model.RemoteAccessInvitation;
import com.sitionix.forgeagent.domain.port.RemoteAccessInvitationGrants;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import jdk.net.ExtendedSocketOptions;
import org.springframework.stereotype.Component;

/** Fixed local supervisor operations, never shell commands or caller-selected filesystem paths. */
@Component
public final class LocalInvitationGrants implements RemoteAccessInvitationGrants {
    private final Path socket;
    private final String supervisorUser;
    public LocalInvitationGrants() { this(Path.of("/run/forge-remote/admin/invitations.sock"),"root"); }
    LocalInvitationGrants(Path socket,String supervisorUser) { this.socket=socket; this.supervisorUser=supervisorUser; }

    @Override public String hostPublicKey() {
        return LocalPairingTokens.validatedPublicKey(request("HOST\n"));
    }
    @Override public void install(RemoteAccessInvitation invitation) { change("INSTALL",invitation); }
    @Override public void remove(RemoteAccessInvitation invitation) { change("REMOVE",invitation); }

    private void change(String operation,RemoteAccessInvitation invitation) {
        String key=LocalPairingTokens.validatedPublicKey(invitation.pairingPublicKey());
        String result=request(operation+" "+invitation.grantorInstanceId()+" "+invitation.id()+" "+key+"\n");
        if (!result.equals("OK")) throw new IllegalStateException("Invitation supervisor rejected operation");
    }

    private String request(String frame) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        try (var channel=SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.configureBlocking(false);
            if (!channel.connect(UnixDomainSocketAddress.of(socket))) {
                await(channel,SelectionKey.OP_CONNECT,deadline);
                if (!channel.finishConnect()) throw new IOException("Incomplete connection");
            }
            if (!channel.getOption(ExtendedSocketOptions.SO_PEERCRED).user().getName().equals(supervisorUser)) {
                throw new IOException("Unexpected supervisor identity");
            }
            var outgoing=ByteBuffer.wrap(frame.getBytes(StandardCharsets.US_ASCII));
            while (outgoing.hasRemaining()) { await(channel,SelectionKey.OP_WRITE,deadline); channel.write(outgoing); }
            var incoming=ByteBuffer.allocate(1025);
            while (incoming.hasRemaining()) {
                await(channel,SelectionKey.OP_READ,deadline);
                if (channel.read(incoming)<0) throw new IOException("Incomplete supervisor response");
                int size=incoming.position();
                for (int i=0;i<size;i++) {
                    if (incoming.get(i)=='\n') {
                        if (i!=size-1) throw new IOException("Invalid supervisor response");
                        return new String(incoming.array(),0,i,StandardCharsets.US_ASCII);
                    }
                }
            }
            throw new IOException("Supervisor response limit exceeded");
        } catch (IOException failure) { throw new IllegalStateException("Invitation supervisor unavailable"); }
    }

    private static void await(SocketChannel channel,int operation,long deadline) throws IOException {
        long millis=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
        if (millis<=0) throw new IOException("Supervisor timeout");
        try (var selector=Selector.open()) {
            channel.register(selector,operation);
            if (selector.select(millis)==0) throw new IOException("Supervisor timeout");
        }
    }
}
