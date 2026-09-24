package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import static org.assertj.core.api.Assertions.*;

import com.sitionix.forgeagent.domain.model.RemoteAccessCommand;
import com.sitionix.forgeagent.domain.port.RemoteAccessCommandExecution;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteAccessLocalExecServerTest {
    @TempDir Path temp;

    @Test void typedRequestPreservesLiteralArgumentsStreamsAndExitCode() throws Exception {
        Path socket=socket();
        UUID id=UUID.randomUUID();
        String literal="a b 'quoted' $HOME $(touch /tmp/should-not-exist)";
        var started=new AtomicReference<RemoteAccessCommand>();
        try(var server=new RemoteAccessLocalExecServer(socket,System.getProperty("user.name"),(session,command) -> {
            assertThat(session).isEqualTo(id);started.set(command);
            return execution("result\n","diagnostic\n",7);
        })) {
            server.start();
            try(var client=SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
                var input=new DataInputStream(Channels.newInputStream(client));
                var output=new DataOutputStream(Channels.newOutputStream(client));
                byte[] request=("{\"sessionId\":\""+id+"\",\"argv\":[\"/bin/echo\",\""+literal+"\"],\"cwd\":\"/workspace\",\"timeoutSeconds\":30}").getBytes();
                output.writeInt(request.length);output.write(request);output.flush();
                assertThat(input.readByte()).isEqualTo((byte)'A');assertThat(input.readInt()).isZero();
                output.writeByte('E');output.writeInt(0);output.flush();
                assertThat(Set.of(frame(input),frame(input))).containsExactlyInAnyOrder(new Frame('O',"result\n"),new Frame('R',"diagnostic\n"));
                assertThat(input.readByte()).isEqualTo((byte)'X');assertThat(input.readInt()).isEqualTo(4);
                assertThat(input.readInt()).isEqualTo(7);
            }
            assertThat(started.get().argv()).containsExactly("/bin/echo",literal);
        }
    }

    @Test void deniedOrInvalidRequestNeverStartsExecution() throws Exception {
        Path socket=socket();
        var calls=new AtomicInteger();
        try(var server=new RemoteAccessLocalExecServer(socket,"not-the-current-user",(id,command) -> {calls.incrementAndGet();return execution("","",0);})) {
            server.start();
            try(var client=SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
                assertThat(new DataInputStream(Channels.newInputStream(client)).readByte()).isEqualTo((byte)'F');
            }
        }
        try(var server=new RemoteAccessLocalExecServer(socket,System.getProperty("user.name"),(id,command) -> {calls.incrementAndGet();return execution("","",0);})) {
            server.start();
            assertThat(request(socket,"{\"sessionId\":\""+UUID.randomUUID()+"\",\"argv\":[\"sh\"],\"cwd\":\"/workspace\",\"timeoutSeconds\":30}")).isEqualTo('F');
        }
        assertThat(calls.get()).isZero();
    }

    @Test void disconnectClosesRunningExecution() throws Exception {
        Path socket=socket();
        var closed=new CountDownLatch(1);
        try(var server=new RemoteAccessLocalExecServer(socket,System.getProperty("user.name"),(id,command) -> new RemoteAccessCommandExecution() {
            final PipedInputStream stdout=new PipedInputStream();
            @Override public OutputStream stdin() {return OutputStream.nullOutputStream();}
            @Override public InputStream stdout() {return stdout;}
            @Override public InputStream stderr() {return InputStream.nullInputStream();}
            @Override public int await() throws InterruptedException {closed.await();return 130;}
            @Override public void close() {closed.countDown();try {stdout.close();}catch(IOException ignored) {}}
        })) {
            server.start();
            try(var client=SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
                var input=new DataInputStream(Channels.newInputStream(client));
                var output=new DataOutputStream(Channels.newOutputStream(client));
                byte[] request=("{\"sessionId\":\""+UUID.randomUUID()+"\",\"argv\":[\"/bin/sleep\",\"120\"],\"cwd\":\"/workspace\",\"timeoutSeconds\":150}").getBytes();
                output.writeInt(request.length);output.write(request);output.flush();
                assertThat(input.readByte()).isEqualTo((byte)'A');assertThat(input.readInt()).isZero();
            }
            assertThat(closed.await(3,TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void unsafeDirectoryOrExistingSocketCannotBeReplaced() throws Exception {
        Path socket=socket();
        Files.setPosixFilePermissions(socket.getParent(),PosixFilePermissions.fromString("rwxrwx---"));
        try(var server=new RemoteAccessLocalExecServer(socket,System.getProperty("user.name"),(id,command) -> execution("","",0))) {
            assertThatThrownBy(server::start).isInstanceOf(IOException.class);
        }
        Files.setAttribute(socket.getParent(),"unix:mode",02750);
        Files.writeString(socket,"preserve");
        try(var server=new RemoteAccessLocalExecServer(socket,System.getProperty("user.name"),(id,command) -> execution("","",0))) {
            assertThatThrownBy(server::start).isInstanceOf(IOException.class);
        }
        assertThat(Files.readString(socket)).isEqualTo("preserve");
    }

    @Test void abandonedManagedSocketIsRecoveredButLiveListenerIsPreserved() throws Exception {
        Path socket=socket();
        try(var abandoned=ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            abandoned.bind(UnixDomainSocketAddress.of(socket));
            Files.setPosixFilePermissions(socket,PosixFilePermissions.fromString("rw-rw----"));
        }
        try(var server=new RemoteAccessLocalExecServer(socket,System.getProperty("user.name"),(id,command) -> execution("","",0))) {
            server.start();
            try(var rival=new RemoteAccessLocalExecServer(socket,System.getProperty("user.name"),(id,command) -> execution("","",0))) {
                assertThatThrownBy(rival::start).isInstanceOf(IOException.class);
            }
            assertThat(socket).exists();
        }
        assertThat(socket).doesNotExist();
    }

    private Path socket() throws IOException {
        Path directory=temp.resolve("operator");Files.createDirectory(directory);
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwxr-x---"));
        Files.setAttribute(directory,"unix:mode",02750);
        return directory.resolve("agent.sock");
    }
    private static char request(Path socket,String json) throws Exception {
        try(var client=SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            var input=new DataInputStream(Channels.newInputStream(client));var output=new DataOutputStream(Channels.newOutputStream(client));
            byte[] value=json.getBytes();output.writeInt(value.length);output.write(value);output.flush();
            try {return (char)input.readByte();} catch(EOFException rejected) {return 'F';}
        }
    }
    private static Frame frame(DataInputStream input) throws IOException {
        char kind=(char)input.readByte();int size=input.readInt();return new Frame(kind,new String(input.readNBytes(size)));
    }
    private record Frame(char kind,String data) {}
    private static RemoteAccessCommandExecution execution(String stdout,String stderr,int result) {
        return new RemoteAccessCommandExecution() {
            @Override public OutputStream stdin() {return OutputStream.nullOutputStream();}
            @Override public InputStream stdout() {return new ByteArrayInputStream(stdout.getBytes());}
            @Override public InputStream stderr() {return new ByteArrayInputStream(stderr.getBytes());}
            @Override public int await() {return result;}
            @Override public void close() {}
        };
    }
}
