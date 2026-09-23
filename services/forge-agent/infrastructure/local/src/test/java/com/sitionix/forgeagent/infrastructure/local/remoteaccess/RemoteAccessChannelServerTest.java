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

    @Test void pairingFrameUsesInvitationAuthorityNotSessionAuthority() throws Exception {
        Path socket = prepare();
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var authority = new com.sitionix.forgeagent.domain.port.RemoteAccessChannelAuthority() {
            @Override public Optional<RemoteAccessSessionStatus> sessionStatus(com.sitionix.forgeagent.domain.model.RemoteAccessKeyBinding binding) {
                throw new AssertionError("Pairing must not query sessions");
            }
            @Override public boolean pairingAllowed(com.sitionix.forgeagent.domain.model.RemoteAccessInvitationBinding binding) {
                return allowed.get();
            }
        };
        try (var server = new RemoteAccessChannelServer(socket, System.getProperty("user.name"),
                Files.readAttributes(temp, java.nio.file.attribute.PosixFileAttributes.class).group().getName(), authority, deniedPeer())) {
            server.start();
            assertThat(request(socket, FRAME.replace("STATUS", "PAIR"))).isEqualTo("PAIRING_ALLOWED\n");
            allowed.set(false);
            assertThat(request(socket, FRAME.replace("STATUS", "PAIR"))).isEqualTo("DENIED\n");
        }
    }

    @Test void validBindingReachesAuthorityButInvalidFramesDoNot() throws Exception {
        var calls = new AtomicInteger();
        Path socket = prepare();
        try (var server = new RemoteAccessChannelServer(socket, System.getProperty("user.name"),
                Files.readAttributes(temp, java.nio.file.attribute.PosixFileAttributes.class).group().getName(),
                binding -> { calls.incrementAndGet(); return Optional.of(RemoteAccessSessionStatus.ACTIVE); }, deniedPeer())) {
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
                binding -> { throw new AssertionError("Foreign peer reached authority"); }, deniedPeer())) {
            server.start();
            assertThat(request(socket,FRAME)).isEqualTo("DENIED\n");
        }
        try (var server = new RemoteAccessChannelServer(socket,System.getProperty("user.name"),group,
                binding -> { throw new IllegalStateException("synthetic secret must not leave socket"); }, deniedPeer())) {
            server.start();
            assertThat(request(socket,FRAME)).isEqualTo("DENIED\n");
        }
    }

    @Test void existingSocketPathIsNotRemovedOrOverwritten() throws Exception {
        Path socket = prepare();
        Files.writeString(socket,"existing-control-file");
        try (var server = new RemoteAccessChannelServer(socket,System.getProperty("user.name"),
                Files.readAttributes(temp, java.nio.file.attribute.PosixFileAttributes.class).group().getName(),
                binding -> Optional.empty(), deniedPeer())) {
            assertThatThrownBy(server::start).isInstanceOf(IllegalStateException.class);
        }
        assertThat(Files.readString(socket)).isEqualTo("existing-control-file");
    }

    @Test void typedRedeemAndConfirmDispatchOnlyStrictPublicMetadata() throws Exception {
        Path socket = prepare();
        UUID id = UUID.randomUUID();
        var calls = new AtomicInteger();
        var peer = new com.sitionix.forgeagent.domain.port.RemoteAccessPeerPairing() {
            public UUID redeem(com.sitionix.forgeagent.domain.model.RemoteAccessInvitationBinding binding,
                    com.sitionix.forgeagent.domain.model.RemoteAccessPairingRequest value) {
                assertThat(value.sessionId()).isEqualTo(id);
                assertThat(value.accessorDisplayName()).isEqualTo("Accessor");
                calls.incrementAndGet(); return id;
            }
            public Optional<RemoteAccessSessionStatus> confirm(com.sitionix.forgeagent.domain.model.RemoteAccessKeyBinding binding) {
                return Optional.of(RemoteAccessSessionStatus.ACTIVE);
            }
        };
        try (var server = new RemoteAccessChannelServer(socket, System.getProperty("user.name"),
                Files.readAttributes(temp, java.nio.file.attribute.PosixFileAttributes.class).group().getName(),
                binding -> Optional.empty(), peer)) {
            server.start();
            String json = "{\"sessionId\":\"" + id + "\",\"accessorInstanceId\":\"" + UUID.randomUUID()
                    + "\",\"accessorDisplayName\":\"Accessor\",\"sessionPublicKey\":\"ssh-ed25519 public\"}";
            String prefix = FRAME.replace("STATUS", "REDEEM").strip() + " ";
            assertThat(request(socket, prefix + encode(json) + "\n")).isEqualTo("PROVISIONING " + id + "\n");
            assertThat(request(socket, FRAME.replace("STATUS", "CONFIRM"))).isEqualTo("ACTIVE\n");
            for (String invalid : new String[]{json.replace("\"Accessor\"", "3"), json + " {}",
                    json.replace("\"Accessor\"", "true"), json.replace("\"Accessor\"", "[]"),
                    json.replace("\"Accessor\"", "null"), json.replace("\"sessionId\":", "\"unknown\":"),
                    json.replace("\"Accessor\"", "3.5"), json.replace("\"accessorDisplayName\":", "\"accessorDisplayName\":\"duplicate\",\"accessorDisplayName\":")}) {
                assertThat(request(socket, prefix + encode(invalid) + "\n")).isEqualTo("DENIED\n");
            }
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    @Test void killedJavaServerLeavesSocketThatRestartCanRecover() throws Exception {
        Path socket=prepare();
        String group=Files.readAttributes(temp,java.nio.file.attribute.PosixFileAttributes.class).group().getName();
        var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",System.getProperty("java.class.path"),CrashPeer.class.getName(),socket.toString(),group)
                .redirectErrorStream(true).start();
        try {
            var ready=java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream())).readLine(); }
                catch(java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
            assertThat(ready.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("READY");
            Object original=Files.readAttributes(socket,java.nio.file.attribute.BasicFileAttributes.class).fileKey();
            try(var competing=server(socket)) {
                assertThatThrownBy(competing::start).isInstanceOf(IllegalStateException.class);
            }
            assertThat(Files.readAttributes(socket,java.nio.file.attribute.BasicFileAttributes.class).fileKey()).isEqualTo(original);
            assertThat(request(socket,FRAME)).isEqualTo("DENIED\n");
            child.destroyForcibly(); // SIGKILL: no Java close/shutdown hook can unlink the socket.
            assertThat(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(socket).exists();
            try(var restarted=server(socket)) {
                restarted.start();
                assertThat(request(socket,FRAME)).isEqualTo("DENIED\n");
            }
            assertThat(socket).doesNotExist();
        } finally { child.destroyForcibly(); child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS); }
    }

    @Test void activeUnmanagedListenerAndUnexpectedSocketModeArePreserved() throws Exception {
        Path socket=prepare();
        try(var existing=java.nio.channels.ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            existing.bind(UnixDomainSocketAddress.of(socket));
            Files.setPosixFilePermissions(socket,PosixFilePermissions.fromString("rw-rw----"));
            Object original=Files.readAttributes(socket,java.nio.file.attribute.BasicFileAttributes.class).fileKey();
            try(var rejected=server(socket)) { assertThatThrownBy(rejected::start).isInstanceOf(IllegalStateException.class); }
            assertThat(Files.readAttributes(socket,java.nio.file.attribute.BasicFileAttributes.class).fileKey()).isEqualTo(original);
            try(var probe=SocketChannel.open(StandardProtocolFamily.UNIX)) { assertThat(probe.connect(UnixDomainSocketAddress.of(socket))).isTrue(); }
        }
        Files.setPosixFilePermissions(socket,PosixFilePermissions.fromString("rw-------"));
        try(var rejected=server(socket)) { assertThatThrownBy(rejected::start).isInstanceOf(IllegalStateException.class); }
        assertThat(socket).exists();
        assertThat(Files.getPosixFilePermissions(socket)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test void socketSymlinkAndUnsafeLockPathsAreNeverReplaced() throws Exception {
        Path socket=prepare();
        Path foreign=Files.writeString(temp.resolve("foreign"),"preserved");
        Files.createSymbolicLink(socket,foreign);
        try(var rejected=server(socket)) { assertThatThrownBy(rejected::start).isInstanceOf(IllegalStateException.class); }
        assertThat(Files.isSymbolicLink(socket)).isTrue();
        assertThat(Files.readString(foreign)).isEqualTo("preserved");
        Files.delete(socket);
        Path lock=socket.resolveSibling(socket.getFileName()+".lock");
        Files.deleteIfExists(lock);
        Files.createSymbolicLink(lock,foreign);
        try(var rejected=server(socket)) { assertThatThrownBy(rejected::start).isInstanceOf(IllegalStateException.class); }
        assertThat(Files.isSymbolicLink(lock)).isTrue();
        Files.delete(lock);
        Files.writeString(lock,"unsafe lock");
        Files.setPosixFilePermissions(lock,PosixFilePermissions.fromString("rw-rw----"));
        try(var rejected=server(socket)) { assertThatThrownBy(rejected::start).isInstanceOf(IllegalStateException.class); }
        assertThat(Files.readString(lock)).isEqualTo("unsafe lock");
        Files.setPosixFilePermissions(lock,PosixFilePermissions.fromString("rw-------"));
        Files.createLink(temp.resolve("second-link"),lock);
        try(var rejected=server(socket)) { assertThatThrownBy(rejected::start).isInstanceOf(IllegalStateException.class); }
        assertThat(socket).doesNotExist();
    }

    @Test void lockRemainsOwnedForLifetimeAndClosingLoserCannotRemoveWinnerSocket() throws Exception {
        Path socket=prepare();
        Path lock=socket.resolveSibling(socket.getFileName()+".lock");
        try(var winner=server(socket)) {
            winner.start();
            Object lockKey=Files.readAttributes(lock,java.nio.file.attribute.BasicFileAttributes.class).fileKey();
            assertThat(Files.getPosixFilePermissions(lock)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            try(var loser=server(socket)) { assertThatThrownBy(loser::start).isInstanceOf(IllegalStateException.class); }
            assertThat(request(socket,FRAME)).isEqualTo("DENIED\n");
            assertThat(Files.readAttributes(lock,java.nio.file.attribute.BasicFileAttributes.class).fileKey()).isEqualTo(lockKey);
        }
        assertThat(lock).exists();
        try(var next=server(socket)) { next.start();assertThat(request(socket,FRAME)).isEqualTo("DENIED\n"); }
    }

    private RemoteAccessChannelServer server(Path socket) throws Exception {
        return new RemoteAccessChannelServer(socket,System.getProperty("user.name"),
                Files.readAttributes(temp,java.nio.file.attribute.PosixFileAttributes.class).group().getName(),
                binding -> Optional.empty(),deniedPeer());
    }
    public static final class CrashPeer {
        public static void main(String[] args) throws Exception {
            try(var server=new RemoteAccessChannelServer(Path.of(args[0]),System.getProperty("user.name"),args[1],
                    binding -> Optional.empty(),deniedPeer())) {
                server.start();System.out.println("READY");System.out.flush();
                new java.util.concurrent.CountDownLatch(1).await();
            }
        }
    }

    private static String encode(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static com.sitionix.forgeagent.domain.port.RemoteAccessPeerPairing deniedPeer() {
        return new com.sitionix.forgeagent.domain.port.RemoteAccessPeerPairing() {
            public UUID redeem(com.sitionix.forgeagent.domain.model.RemoteAccessInvitationBinding binding,
                    com.sitionix.forgeagent.domain.model.RemoteAccessPairingRequest request) { throw new IllegalStateException(); }
            public Optional<RemoteAccessSessionStatus> confirm(com.sitionix.forgeagent.domain.model.RemoteAccessKeyBinding binding) { return Optional.empty(); }
        };
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
