package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.model.RemoteAccessSessionStatus;
import java.nio.channels.SocketChannel;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteAccessChannelServerTest {
    @TempDir Path temp;
    private static final String FRAME = "STATUS " + UUID.randomUUID() + " " + UUID.randomUUID() + " SHA256:" + "A".repeat(43) + "\n";

    @Test void validBindingReachesAuthorityButInvalidFramesDoNot() throws Exception {
        var calls = new AtomicInteger();
        Path socket = prepare();
        try (var server = new RemoteAccessChannelServer(socket, System.getProperty("user.name"),
                Files.readAttributes(temp, java.nio.file.attribute.PosixFileAttributes.class).group().getName(),
                binding -> { calls.incrementAndGet(); return Optional.of(RemoteAccessSessionStatus.ACTIVE); })) {
            server.start();
            assertThat(request(socket,FRAME)).isEqualTo("ACTIVE\n");
            for (String invalid : new String[]{"exec id\n", "STATUS bad\n", FRAME.strip()+" extra\n", "x".repeat(1025)+"\n"}) {
                assertThat(request(socket,invalid)).isEqualTo("DENIED\n");
            }
            assertThat(calls.get()).isEqualTo(1);
        }
        assertThat(socket).doesNotExist();
    }

    @Test void wrongPeerAndUnavailableAuthorityFailClosed() throws Exception {
        Path socket = prepare();
        String group = Files.readAttributes(temp, java.nio.file.attribute.PosixFileAttributes.class).group().getName();
        try (var server = new RemoteAccessChannelServer(socket,"not-the-current-user",group,
                binding -> { throw new AssertionError("Foreign peer reached authority"); })) {
            server.start();
            assertThat(request(socket,FRAME)).isEqualTo("DENIED\n");
        }
        try (var server = new RemoteAccessChannelServer(socket,System.getProperty("user.name"),group,
                binding -> { throw new IllegalStateException("synthetic secret must not leave socket"); })) {
            server.start();
            assertThat(request(socket,FRAME)).isEqualTo("DENIED\n");
        }
    }

    @Test void existingSocketPathIsNotRemovedOrOverwritten() throws Exception {
        Path socket = prepare();
        Files.writeString(socket,"existing-control-file");
        try (var server = new RemoteAccessChannelServer(socket,System.getProperty("user.name"),
                Files.readAttributes(temp, java.nio.file.attribute.PosixFileAttributes.class).group().getName(),
                binding -> Optional.empty())) {
            assertThatThrownBy(server::start).isInstanceOf(IllegalStateException.class);
        }
        assertThat(Files.readString(socket)).isEqualTo("existing-control-file");
    }

    private Path prepare() throws Exception {
        Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rwxr-x---"));
        return temp.resolve("authority.sock");
    }
    private static String request(Path socket,String frame) throws Exception {
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            var bytes=ByteBuffer.wrap(frame.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            while(bytes.hasRemaining()) channel.write(bytes);
            var result=ByteBuffer.allocate(128);
            while (channel.read(result)>0) {
                if (result.get(result.position()-1)=='\n') break;
            }
            return new String(result.array(),0,result.position(),java.nio.charset.StandardCharsets.US_ASCII);
        }
    }
}
