package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sitionix.forgeagent.domain.model.RemoteAccessCommand;
import com.sitionix.forgeagent.domain.port.RemoteAccessCommandExecution;
import java.io.*;
import java.net.ConnectException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import jdk.net.ExtendedSocketOptions;

/** Local operator-only streaming bridge to the existing ACCESSOR execution use case. */
public final class RemoteAccessLocalExecServer implements AutoCloseable {
    private static final int MAX_REQUEST=16_384;
    private static final int MAX_FRAME=65_536;
    private static final ConcurrentHashMap<Path,RemoteAccessLocalExecServer> OWNERS=new ConcurrentHashMap<>();
    private static final JsonMapper JSON=JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).build();

    @FunctionalInterface public interface Starter {
        RemoteAccessCommandExecution start(UUID sessionId,RemoteAccessCommand command);
    }
    private record Request(UUID sessionId,java.util.List<String> argv,String cwd,int timeoutSeconds) {}

    private final Path path;
    private final String operatorUser;
    private final Starter starter;
    private final Semaphore slots=new Semaphore(8);
    private final Set<SocketChannel> channels=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private ServerSocketChannel listener;
    private Object socketFileKey;
    private FileChannel lockChannel;
    private FileLock lifecycleLock;

    public RemoteAccessLocalExecServer(Path path,String operatorUser,Starter starter) {
        this.path=path.toAbsolutePath().normalize();
        this.operatorUser=operatorUser;
        this.starter=starter;
    }

    public synchronized void start() throws IOException {
        if(listener!=null) throw new IllegalStateException("Local execution already started");
        if(operatorUser==null || operatorUser.isBlank()) throw new IllegalStateException("Explicit local operator required");
        for(Path current=path.getParent();current!=null;current=current.getParent()) {
            if(Files.isSymbolicLink(current)) throw new IOException("Symlink in local execution path");
        }
        var directory=Files.readAttributes(path.getParent(),PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        int directoryMode=((Number)Files.getAttribute(path.getParent(),"unix:mode",LinkOption.NOFOLLOW_LINKS)).intValue() & 07777;
        if(!directory.isDirectory() || !directory.owner().getName().equals(System.getProperty("user.name"))
                || directoryMode!=02750) {
            throw new IOException("Protected local execution directory required");
        }
        try {
            acquireLock();
            recoverAbandonedSocket(directory);
            listener=ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            listener.bind(UnixDomainSocketAddress.of(path),8);
            socketFileKey=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
            if(!Files.readAttributes(path,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS).group().equals(directory.group())) {
                throw new IOException("Local execution socket did not inherit protected operator group");
            }
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-rw----"));
            Thread.ofVirtual().name("remote-local-exec-accept").start(this::accept);
        } catch(IOException | RuntimeException failure) {
            close();throw failure;
        }
    }

    private void acquireLock() throws IOException {
        if(OWNERS.putIfAbsent(path,this)!=null) throw new IOException("Local execution listener already owned");
        Path lock=path.resolveSibling(path.getFileName()+".lock");
        try {
            try {
                lockChannel=FileChannel.open(lock,Set.of(StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch(FileAlreadyExistsException existing) {
                Object original=validateLock(lock).fileKey();
                lockChannel=FileChannel.open(lock,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
                if(!original.equals(validateLock(lock).fileKey())) throw new IOException("Local execution lock changed");
            }
            Object original=validateLock(lock).fileKey();
            try {lifecycleLock=lockChannel.tryLock();}
            catch(OverlappingFileLockException busy) {throw new IOException("Local execution listener already active",busy);}
            if(lifecycleLock==null || !original.equals(validateLock(lock).fileKey())) {
                throw new IOException("Local execution listener already active or lock changed");
            }
        } catch(IOException | RuntimeException failure) {
            if(lockChannel!=null) try {lockChannel.close();}catch(IOException ignored) { }
            lockChannel=null;OWNERS.remove(path,this);throw failure;
        }
    }

    private static PosixFileAttributes validateLock(Path lock) throws IOException {
        var attrs=Files.readAttributes(lock,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attrs.isRegularFile() || !attrs.owner().getName().equals(System.getProperty("user.name"))
                || !attrs.permissions().equals(PosixFilePermissions.fromString("rw-------"))
                || ((Number)Files.getAttribute(lock,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()!=1) {
            throw new IOException("Protected local execution lock required");
        }
        return attrs;
    }

    private void recoverAbandonedSocket(PosixFileAttributes directory) throws IOException {
        PosixFileAttributes original;
        try {original=validateSocket(directory);} catch(NoSuchFileException absent) {return;}
        try(var probe=SocketChannel.open(StandardProtocolFamily.UNIX)) {
            probe.connect(UnixDomainSocketAddress.of(path));
            throw new IOException("Existing local execution socket has an active listener");
        } catch(ConnectException refused) {
            if(!"Connection refused".equals(refused.getMessage())) throw refused;
        }
        if(!original.fileKey().equals(validateSocket(directory).fileKey())) throw new IOException("Local execution socket changed");
        Files.delete(path);
    }

    private PosixFileAttributes validateSocket(PosixFileAttributes directory) throws IOException {
        var attrs=Files.readAttributes(path,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        int mode=((Number)Files.getAttribute(path,"unix:mode",LinkOption.NOFOLLOW_LINKS)).intValue();
        if((mode & 0170000)!=0140000 || !attrs.owner().getName().equals(System.getProperty("user.name"))
                || !attrs.group().equals(directory.group())
                || !attrs.permissions().equals(PosixFilePermissions.fromString("rw-rw----"))
                || ((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()!=1) {
            throw new IOException("Existing local execution path is not a managed socket");
        }
        return attrs;
    }

    private void accept() {
        while(listener.isOpen()) {
            try {
                SocketChannel channel=listener.accept();
                if(!slots.tryAcquire()) {channel.close();continue;}
                channels.add(channel);
                Thread.ofVirtual().name("remote-local-exec").start(() -> {
                    try {serve(channel);} finally {channels.remove(channel);slots.release();}
                });
            } catch(IOException stopped) {return;}
        }
    }

    private void serve(SocketChannel channel) {
        try(channel) {
            var peer=channel.getOption(ExtendedSocketOptions.SO_PEERCRED);
            if(!peer.user().getName().equals(operatorUser)) {failure(channel);return;}
            var input=new DataInputStream(Channels.newInputStream(channel));
            var output=new DataOutputStream(Channels.newOutputStream(channel));
            int length=input.readInt();
            if(length<1 || length>MAX_REQUEST) {failure(output);return;}
            var request=JSON.readValue(input.readNBytes(length),Request.class);
            if(request.sessionId()==null) {failure(output);return;}
            var command=new RemoteAccessCommand(request.argv(),request.cwd(),request.timeoutSeconds());
            try(var running=starter.start(request.sessionId(),command)) {
                frame(output,'A',new byte[0]);
                var finished=new AtomicBoolean();
                Thread.ofVirtual().name("remote-local-exec-stdin").start(() -> receive(input,running,finished));
                Thread.ofVirtual().name("remote-local-exec-disconnect").start(() -> {
                    while(!finished.get()) {
                        try {Thread.sleep(250);}
                        catch(InterruptedException stopped) {Thread.currentThread().interrupt();return;}
                        if(finished.get()) return;
                        try {frame(output,'P',new byte[0]);}
                        catch(IOException disconnected) {running.close();return;}
                    }
                });
                Thread stdout=Thread.ofVirtual().start(() -> pump(running.stdout(),output,'O'));
                Thread stderr=Thread.ofVirtual().start(() -> pump(running.stderr(),output,'R'));
                int code=running.await();
                stdout.join();stderr.join();
                finished.set(true);
                byte[] result=java.nio.ByteBuffer.allocate(4).putInt(code).array();
                frame(output,'X',result);
            }
        } catch(Exception rejected) {
            try {failure(channel);} catch(Exception ignored) { }
        }
    }

    private static void receive(DataInputStream input,RemoteAccessCommandExecution running,AtomicBoolean finished) {
        try {
            boolean ended=false;
            while(!finished.get()) {
                int kind=input.read();
                if(kind<0) {if(!finished.get()) running.close();return;}
                int length=input.readInt();
                if(length<0 || length>MAX_FRAME || kind!='I' && kind!='E' || kind=='E' && length!=0 || ended) {
                    running.close();return;
                }
                if(kind=='E') {running.stdin().close();ended=true;continue;}
                byte[] bytes=input.readNBytes(length);
                if(bytes.length!=length) {running.close();return;}
                running.stdin().write(bytes);running.stdin().flush();
            }
        } catch(IOException | RuntimeException disconnected) {
            if(!finished.get()) running.close();
        }
    }

    private static void pump(InputStream source,DataOutputStream output,char kind) {
        byte[] buffer=new byte[8192];
        try {
            int count;
            while((count=source.read(buffer))>=0) frame(output,kind,java.util.Arrays.copyOf(buffer,count));
        } catch(IOException disconnected) { }
    }

    private static void failure(SocketChannel channel) throws IOException {
        failure(new DataOutputStream(Channels.newOutputStream(channel)));
    }
    private static void failure(DataOutputStream output) throws IOException {
        frame(output,'F',"Remote execution unavailable".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    private static void frame(DataOutputStream output,char kind,byte[] payload) throws IOException {
        synchronized(output) {
            output.writeByte(kind);output.writeInt(payload.length);output.write(payload);output.flush();
        }
    }

    @Override public synchronized void close() {
        if(listener!=null) try {listener.close();}catch(IOException ignored) { }
        for(var channel:channels) try {channel.close();}catch(IOException ignored) { }
        if(socketFileKey!=null) {
            try {
                if(socketFileKey.equals(Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey())) Files.delete(path);
            } catch(IOException ignored) { }
        }
        if(lifecycleLock!=null) try {lifecycleLock.release();}catch(IOException ignored) { }
        if(lockChannel!=null) try {lockChannel.close();}catch(IOException ignored) { }
        OWNERS.remove(path,this);
        listener=null;socketFileKey=null;
    }
}
