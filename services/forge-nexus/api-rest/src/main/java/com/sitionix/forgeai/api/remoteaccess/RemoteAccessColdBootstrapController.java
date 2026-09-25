package com.sitionix.forgeai.api.remoteaccess;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cold local entry point; the dedicated Remote Access API starts on demand. */
@RestController
@ConditionalOnProperty(name="forge.remote-access.enabled",havingValue="false",matchIfMissing=true)
@RequestMapping("/api/v1/infrastructure/agents/remote-access/bootstrap")
public class RemoteAccessColdBootstrapController {
    private static final String ORIGIN="http://127.0.0.1:9099";
    private static final String CSRF="forge.remote-access.bootstrap-csrf";
    private static final SecureRandom RANDOM=new SecureRandom();
    private final Bridge bridge;

    public RemoteAccessColdBootstrapController() { this(new SystemBridge()); }
    RemoteAccessColdBootstrapController(Bridge bridge) { this.bridge=bridge; }

    interface Bridge {
        boolean ready();
        default boolean failed() { return false; }
        void prepare();
    }
    public record State(String status,String csrfToken) {}

    @GetMapping public State state(HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        if (!local(request)) { response.setStatus(403);return new State("FORBIDDEN",null); }
        var session=request.getSession(true);
        String csrf=(String)session.getAttribute(CSRF);
        if (csrf==null) {
            byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);
            csrf=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            session.setAttribute(CSRF,csrf);
        }
        return new State(bridge.ready()?"READY":bridge.failed()?"FAILED":"COLD",csrf);
    }

    @PostMapping public ResponseEntity<State> prepare(HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        var session=request.getSession(false);
        var expected=session==null?null:session.getAttribute(CSRF);
        var token=request.getHeader("X-CSRF-TOKEN");
        if (!local(request) || !ORIGIN.equals(request.getHeader("Origin"))
                || !(expected instanceof String value) || token==null
                || !MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(403).body(new State("FORBIDDEN",null));
        }
        if (bridge.ready()) return ResponseEntity.ok(new State("READY",null));
        try {
            bridge.prepare();
            return ResponseEntity.accepted().body(new State("PREPARING",null));
        } catch (RuntimeException failure) {
            return ResponseEntity.status(503).body(new State("SETUP_UNAVAILABLE",null));
        }
    }

    private static boolean local(HttpServletRequest request) {
        try {
            String site=request.getHeader("Sec-Fetch-Site");
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()
                && ORIGIN.substring("http://".length()).equals(request.getHeader("Host"))
                && (site==null || site.equals("same-origin") || site.equals("none"))
                && Collections.list(request.getHeaders("Host")).size()==1
                && Collections.list(request.getHeaders("Origin")).size()<=1
                && Collections.list(request.getHeaders("X-CSRF-TOKEN")).size()<=1
                && Collections.list(request.getHeaderNames()).stream().noneMatch(name ->
                    name.equalsIgnoreCase("Forwarded") || name.toLowerCase(Locale.ROOT).startsWith("x-forwarded-"));
        } catch (Exception invalid) { return false; }
    }

    static final class SystemBridge implements Bridge {
        private final Path socketPath;
        private final Path systemctlPath;
        SystemBridge() { this(Path.of("/run/forge-remote-bootstrap.sock")); }
        SystemBridge(Path socketPath) { this(socketPath,Path.of("/usr/bin/systemctl")); }
        SystemBridge(Path socketPath,Path systemctlPath) { this.socketPath=socketPath;this.systemctlPath=systemctlPath; }
        public boolean ready() {
            try {
                var connection=(HttpURLConnection)new URL("http://127.0.0.1:9100/fgaisox/actuator/health").openConnection();
                connection.setConnectTimeout(1000);connection.setReadTimeout(1000);
                try { return connection.getResponseCode()==200 && new String(connection.getInputStream().readNBytes(256),StandardCharsets.UTF_8).contains("\"UP\""); }
                finally { connection.disconnect(); }
            } catch (Exception unavailable) { return false; }
        }
        public boolean failed() {
            try {
                var process=new ProcessBuilder(systemctlPath.toString(),"show","forge-remote-setup.service",
                        "--property=ActiveState","--property=SubState").redirectError(ProcessBuilder.Redirect.DISCARD).start();
                try {
                    if (!process.waitFor(2,TimeUnit.SECONDS) || process.exitValue()!=0) return true;
                    var state=new String(process.getInputStream().readNBytes(128),StandardCharsets.US_ASCII);
                    var active=state.lines().filter(line -> line.startsWith("ActiveState=")).findFirst().orElse("");
                    var sub=state.lines().filter(line -> line.startsWith("SubState=")).findFirst().orElse("");
                    if (active.equals("ActiveState=activating") || active.equals("ActiveState=inactive") && sub.equals("SubState=dead")
                            || active.equals("ActiveState=active") && sub.equals("SubState=running")) return false;
                    return true;
                } finally { process.destroyForcibly(); }
            } catch (Exception unavailable) { return true; }
        }
        public void prepare() {
            try (var channel=SocketChannel.open(StandardProtocolFamily.UNIX)) {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                channel.configureBlocking(false);
                if (!channel.connect(UnixDomainSocketAddress.of(socketPath))) {
                    await(channel,SelectionKey.OP_CONNECT,deadline);
                    if (!channel.finishConnect()) throw new IllegalStateException("Bootstrap connection unavailable");
                }
                var request=ByteBuffer.wrap("ENABLE\n".getBytes(StandardCharsets.US_ASCII));
                while(request.hasRemaining()) { await(channel,SelectionKey.OP_WRITE,deadline);channel.write(request); }
                channel.shutdownOutput();
                var reply=ByteBuffer.allocate(32);
                while (reply.position()<32) {
                    await(channel,SelectionKey.OP_READ,deadline);
                    int read=channel.read(reply);
                    if (read<0) break;
                    if (read>0 && reply.get(reply.position()-1)=='\n') break;
                }
                reply.flip();
                if (!"PREPARING\n".equals(StandardCharsets.US_ASCII.decode(reply).toString()))
                    throw new IllegalStateException("Remote Access setup unavailable");
            } catch (Exception unavailable) { throw new IllegalStateException("Remote Access setup unavailable",unavailable); }
        }
        private static void await(SocketChannel channel,int interest,long deadline) throws Exception {
            long remaining=deadline-System.nanoTime();
            if (remaining<=0) throw new IllegalStateException("Bootstrap timed out");
            try (var selector=Selector.open()) {
                channel.register(selector,interest);
                if (selector.select(Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)))==0)
                    throw new IllegalStateException("Bootstrap timed out");
            }
        }
    }
}
