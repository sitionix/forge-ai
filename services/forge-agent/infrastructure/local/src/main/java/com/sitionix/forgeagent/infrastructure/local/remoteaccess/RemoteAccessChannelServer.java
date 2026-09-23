package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import com.sitionix.forgeagent.domain.model.RemoteAccessKeyBinding;
import com.sitionix.forgeagent.domain.model.RemoteAccessPairingRequest;
import com.sitionix.forgeagent.domain.model.RemoteAccessSessionStatus;
import com.sitionix.forgeagent.domain.port.RemoteAccessPeerPairing;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.util.Base64;
import com.sitionix.forgeagent.domain.model.RemoteAccessInvitationBinding;
import com.sitionix.forgeagent.domain.port.RemoteAccessChannelAuthority;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.ConnectException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import jdk.net.ExtendedSocketOptions;

/** Restricted local transport, not an HTTP management API or command executor. */
public final class RemoteAccessChannelServer implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer,CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float,CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean,CoercionAction.Fail)).build();
    // Opening/closing a second descriptor can release POSIX locks owned by this JVM.
    // Reject duplicate local lifecycles before opening their lock file.
    private static final ConcurrentHashMap<Path,RemoteAccessChannelServer> LOCAL_OWNERS=new ConcurrentHashMap<>();
    private final RemoteAccessPeerPairing peerPairing;
    private final Path path;
    private final String peerUser;
    private final String peerGroup;
    private final RemoteAccessChannelAuthority authority;
    private final com.sitionix.forgeagent.domain.port.RemoteAccessPeerExecution execution;
    private final Set<SocketChannel> channels = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4),r -> { var t=new Thread(r,"remote-access-channel"); t.setDaemon(true); return t; });
    private ServerSocketChannel listener;
    private Object socketFileKey;
    private FileChannel lockChannel;
    private FileLock lifecycleLock;
    private boolean closed;

    public RemoteAccessChannelServer(Path path,String peerUser,String peerGroup,RemoteAccessChannelAuthority authority,RemoteAccessPeerPairing peerPairing,com.sitionix.forgeagent.domain.port.RemoteAccessPeerExecution execution) {
        this.path=path.toAbsolutePath().normalize();
        this.peerUser=peerUser;
        this.peerGroup=peerGroup;
        this.authority=authority;
        this.peerPairing=peerPairing;
        this.execution=execution;
    }

    public synchronized void start() {
        if(closed || listener!=null) throw new IllegalStateException("Remote access channel lifecycle already used");
        try {
            validateDirectory();
            acquireLifecycleLock();
            recoverAbandonedSocket();
            listener=ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            listener.bind(UnixDomainSocketAddress.of(path),8);
            socketFileKey=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
            Files.setAttribute(path,"posix:group",path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByGroupName(peerGroup));
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-rw----"));
            var acceptor=new Thread(this::accept,"remote-access-admission");
            acceptor.setDaemon(true);
            acceptor.start();
        } catch (IOException | RuntimeException failure) {
            close();
            throw new IllegalStateException("Remote access channel setup failed",failure);
        }
    }

    private void validateDirectory() throws IOException {
        for (Path current=path.getParent();current!=null;current=current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IllegalStateException("Remote access socket directory cannot be a symlink");
        }
        var attributes=Files.readAttributes(path.getParent(),PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || !attributes.owner().getName().equals(System.getProperty("user.name"))
                || !attributes.group().getName().equals(peerGroup)
                || !attributes.permissions().equals(PosixFilePermissions.fromString("rwxr-x---"))) {
            throw new IllegalStateException("Remote access socket requires a protected control-owned 0750 directory");
        }
    }

    private void acquireLifecycleLock() throws IOException {
        if(LOCAL_OWNERS.putIfAbsent(path,this)!=null) throw new IOException("Remote access channel already owned in this JVM");
        Path lockPath=path.resolveSibling(path.getFileName()+".lock");
        try {
            lockChannel=FileChannel.open(lockPath,Set.of(StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch(FileAlreadyExistsException existing) {
            Object original=validateLockFile(lockPath).fileKey();
            lockChannel=FileChannel.open(lockPath,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            if(!original.equals(validateLockFile(lockPath).fileKey())) throw new IOException("Remote access lock changed");
        }
        Object original=validateLockFile(lockPath).fileKey();
        lifecycleLock=lockChannel.tryLock();
        if(lifecycleLock==null) throw new IOException("Remote access channel already owned by another process");
        if(!original.equals(validateLockFile(lockPath).fileKey())) throw new IOException("Remote access lock changed");
        // Never unlink this file: replacing its inode would permit independent locks on the same socket path.
    }

    private static PosixFileAttributes validateLockFile(Path lockPath) throws IOException {
        var attributes=Files.readAttributes(lockPath,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isRegularFile() || !attributes.owner().getName().equals(System.getProperty("user.name"))
                || !attributes.permissions().equals(PosixFilePermissions.fromString("rw-------"))
                || ((Number)Files.getAttribute(lockPath,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()!=1) {
            throw new IOException("Remote access lifecycle lock requires an owner-only regular file");
        }
        return attributes;
    }

    private void recoverAbandonedSocket() throws IOException {
        PosixFileAttributes original;
        try { original=validateExistingSocket(); }
        catch(NoSuchFileException absent) { return; }
        boolean refused=false;
        try(var probe=SocketChannel.open(StandardProtocolFamily.UNIX)) {
            probe.configureBlocking(false);
            if(!probe.connect(UnixDomainSocketAddress.of(path))) {
                await(probe,SelectionKey.OP_CONNECT,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(250));
                if(!probe.finishConnect()) throw new IOException("Existing socket liveness is unknown");
            }
        } catch(ConnectException failure) {
            // Only the explicit Linux ECONNREFUSED result proves an abandoned UNIX socket.
            // Permission failures, timeouts, missing paths, localized/unknown errors all preserve it.
            if(!"Connection refused".equals(failure.getMessage())) throw failure;
            refused=true;
        }
        if(!refused) throw new IOException("Existing remote access socket has an active listener");
        if(!original.fileKey().equals(validateExistingSocket().fileKey())) throw new IOException("Existing remote access socket changed");
        Files.delete(path);
    }

    private PosixFileAttributes validateExistingSocket() throws IOException {
        var attributes=Files.readAttributes(path,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        int mode=((Number)Files.getAttribute(path,"unix:mode",LinkOption.NOFOLLOW_LINKS)).intValue();
        if((mode & 0170000)!=0140000 || !attributes.owner().getName().equals(System.getProperty("user.name"))
                || !attributes.group().getName().equals(peerGroup)
                || !attributes.permissions().equals(PosixFilePermissions.fromString("rw-rw----"))
                || ((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()!=1) {
            throw new IOException("Existing remote access path is not a managed control socket");
        }
        return attributes;
    }

    private void accept() {
        while (listener.isOpen()) {
            try {
                var channel=listener.accept();
                channels.add(channel);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                try { workers.execute(() -> serve(channel,deadline)); }
                catch (RuntimeException full) { channels.remove(channel); channel.close(); }
            } catch (IOException stopped) { return; }
        }
    }

    private void serve(SocketChannel channel,long deadline) {
        try (channel) {
            var peer=channel.getOption(ExtendedSocketOptions.SO_PEERCRED);
            channel.configureBlocking(false);
            String frame=readFrame(channel,deadline);
            String response="DENIED\n";
            if (peer.user().getName().equals(peerUser)) {
                try {
                    String[] fields=frame.split(" ",-1);
                    if (fields.length==5 && fields[0].equals("EXEC")) {
                        var binding=new RemoteAccessKeyBinding(canonicalUuid(fields[1]),canonicalUuid(fields[2]),fields[3]);
                        execution.start(binding,canonicalUuid(fields[4]));
                        response="STARTED\n";
                    } else if (fields.length==4 && fields[0].equals("REVOKE")) {
                        var binding=new RemoteAccessKeyBinding(canonicalUuid(fields[1]),canonicalUuid(fields[2]),fields[3]);
                        var status=execution.revoke(binding);
                        if (status==RemoteAccessSessionStatus.REVOKING || status==RemoteAccessSessionStatus.REVOKED) response=status.name()+"\n";
                        deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                    } else if (fields.length==5 && fields[0].equals("REDEEM")) {
                        var binding=new RemoteAccessInvitationBinding(canonicalUuid(fields[1]),canonicalUuid(fields[2]),fields[3]);
                        String encoded=fields[4];
                        if (!encoded.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
                        byte[] bytes=Base64.getUrlDecoder().decode(encoded);
                        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) throw new IllegalArgumentException();
                        var request=JSON.readValue(bytes,RemoteAccessPairingRequest.class);
                        if(request.sessionId()==null || request.accessorInstanceId()==null
                                || request.accessorDisplayName()==null || request.accessorDisplayName().isBlank()
                                || request.accessorDisplayName().length()>128
                                || request.accessorDisplayName().chars().anyMatch(Character::isISOControl)
                                || request.sessionPublicKey()==null || request.sessionPublicKey().isBlank()) throw new IllegalArgumentException();
                        UUID sessionId=peerPairing.redeem(binding,request);
                        if (sessionId==null || !sessionId.equals(request.sessionId())) throw new IllegalArgumentException();
                        response="PROVISIONING "+sessionId+"\n";
                    } else if (fields.length==4 && fields[0].equals("CONFIRM")) {
                        var binding=new RemoteAccessKeyBinding(canonicalUuid(fields[1]),canonicalUuid(fields[2]),fields[3]);
                        response=peerPairing.confirm(binding).filter(status -> status==RemoteAccessSessionStatus.ACTIVE)
                                .map(status -> "ACTIVE\n").orElse("DENIED\n");
                    } else if (fields.length==4 && fields[0].equals("STATUS")) {
                        var binding=new RemoteAccessKeyBinding(canonicalUuid(fields[1]),canonicalUuid(fields[2]),fields[3]);
                        response=authority.sessionStatus(binding).map(status -> status.name()+"\n").orElse("DENIED\n");
                    } else if (fields.length==4 && fields[0].equals("PAIR")) {
                        var binding=new RemoteAccessInvitationBinding(canonicalUuid(fields[1]),canonicalUuid(fields[2]),fields[3]);
                        response=authority.pairingAllowed(binding) ? "PAIRING_ALLOWED\n" : "DENIED\n";
                    }
                } catch (IOException | RuntimeException unavailable) { response="DENIED\n"; }
            }
            write(channel,response,deadline);
        } catch (IOException | RuntimeException rejected) {
            // No request, credential, peer exception or internal database details leave this boundary.
        } finally { channels.remove(channel); }
    }

    private static UUID canonicalUuid(String text) {
        UUID value=UUID.fromString(text);
        if (!value.toString().equals(text)) throw new IllegalArgumentException("Non-canonical identity");
        return value;
    }

    private static String readFrame(SocketChannel channel,long deadline) throws IOException {
        var buffer=ByteBuffer.allocate(8193);
        while (buffer.hasRemaining()) {
            await(channel,SelectionKey.OP_READ,deadline);
            int read=channel.read(buffer);
            if (read<0) throw new IOException("Incomplete frame");
            int length=buffer.position();
            for (int i=0;i<length;i++) {
                if (buffer.get(i)=='\n') {
                    if (i!=length-1) return "";
                    return new String(buffer.array(),0,i,StandardCharsets.US_ASCII);
                }
            }
        }
        return "";
    }

    private static void write(SocketChannel channel,String response,long deadline) throws IOException {
        var bytes=ByteBuffer.wrap(response.getBytes(StandardCharsets.US_ASCII));
        while(bytes.hasRemaining()) { await(channel,SelectionKey.OP_WRITE,deadline); channel.write(bytes); }
    }
    private static void await(SocketChannel channel,int operation,long deadline) throws IOException {
        long millis=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
        if(millis<=0) throw new IOException("Channel timeout");
        try(var selector=Selector.open()) {
            channel.register(selector,operation);
            if(selector.select(millis)==0) throw new IOException("Channel timeout");
        }
    }

    @Override public synchronized void close() {
        if(closed) return;
        closed=true;
        if(listener!=null) try { listener.close(); } catch(IOException ignored) { }
        for(var channel:channels) try { channel.close(); } catch(IOException ignored) { }
        workers.shutdownNow();
        if(socketFileKey!=null) {
            try {
                var current=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
                if(socketFileKey.equals(current)) Files.delete(path);
            } catch(IOException ignored) { }
        }
        if(lifecycleLock!=null) try { lifecycleLock.release(); } catch(IOException ignored) { }
        if(lockChannel!=null) try { lockChannel.close(); } catch(IOException ignored) { }
        LOCAL_OWNERS.remove(path,this);
    }
}
