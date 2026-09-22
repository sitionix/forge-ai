package com.sitionix.forgeagent.infrastructure.local.remoteaccess;

import com.sitionix.forgeagent.domain.model.RemoteAccessKeyBinding;
import com.sitionix.forgeagent.domain.port.RemoteAccessChannelAuthority;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private final Path path;
    private final String peerUser;
    private final String peerGroup;
    private final RemoteAccessChannelAuthority authority;
    private final Set<SocketChannel> channels = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4),r -> { var t=new Thread(r,"remote-access-channel"); t.setDaemon(true); return t; });
    private ServerSocketChannel listener;
    private Object socketFileKey;

    public RemoteAccessChannelServer(Path path,String peerUser,String peerGroup,RemoteAccessChannelAuthority authority) {
        this.path=path.toAbsolutePath().normalize();
        this.peerUser=peerUser;
        this.peerGroup=peerGroup;
        this.authority=authority;
    }

    public void start() {
        try {
            validateDirectory();
            if (Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Remote access socket path already exists; operator cleanup required");
            }
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
                    if (fields.length==4 && fields[0].equals("STATUS")) {
                        var binding=new RemoteAccessKeyBinding(canonicalUuid(fields[1]),canonicalUuid(fields[2]),fields[3]);
                        response=authority.sessionStatus(binding).map(status -> status.name()+"\n").orElse("DENIED\n");
                    }
                } catch (RuntimeException unavailable) { response="DENIED\n"; }
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
        var buffer=ByteBuffer.allocate(1025);
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

    @Override public void close() {
        if(listener!=null) try { listener.close(); } catch(IOException ignored) { }
        for(var channel:channels) try { channel.close(); } catch(IOException ignored) { }
        workers.shutdownNow();
        if(socketFileKey!=null) {
            try {
                var current=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
                if(socketFileKey.equals(current)) Files.delete(path);
            } catch(IOException ignored) { }
        }
    }
}
