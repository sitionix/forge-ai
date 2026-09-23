package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import jdk.net.ExtendedSocketOptions;

/** Bounded local UNIX connection with verified supervisor identity; no command retry. */
final class RemoteAccessSupervisorConnection {
    private RemoteAccessSupervisorConnection() { }
    static String exchange(Path socket,String supervisorUser,String frame,int seconds) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
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
        } catch (IOException failure) { throw new IllegalStateException("Remote access supervisor unavailable"); }
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
