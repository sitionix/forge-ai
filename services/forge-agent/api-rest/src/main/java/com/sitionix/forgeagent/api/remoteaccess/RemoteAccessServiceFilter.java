package com.sitionix.forgeagent.api.remoteaccess;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/** Dedicated local service identity; SSH keys never authenticate management HTTP. */
public final class RemoteAccessServiceFilter implements Filter {
    private final byte[] credential;
    public RemoteAccessServiceFilter(Path path) { credential=readCredential(path).getBytes(StandardCharsets.UTF_8); }
    public static String readCredential(Path path) {
        try {
            var absolute=path.toAbsolutePath().normalize();
            for (Path part=absolute;part!=null;part=part.getParent()) {
                if (Files.isSymbolicLink(part)) throw new IllegalStateException("Management credential path must not contain symlinks");
                if (!part.equals(absolute)) {
                    var mode=((Number)Files.getAttribute(part,"unix:mode",LinkOption.NOFOLLOW_LINKS)).intValue();
                    if ((mode & 0022)!=0 && (mode & 01000)==0) throw new IllegalStateException("Management credential ancestor is writable");
                }
            }
            var permissions=Files.getPosixFilePermissions(absolute,LinkOption.NOFOLLOW_LINKS);
            if (!Files.isRegularFile(absolute,LinkOption.NOFOLLOW_LINKS)
                    || !Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE).containsAll(permissions)
                    || !permissions.contains(PosixFilePermission.OWNER_READ) || Files.size(absolute)>256) {
                throw new IllegalStateException("Management credential requires a protected owner-only file");
            }
            var owner=Files.getOwner(absolute,LinkOption.NOFOLLOW_LINKS).getName();
            if (!owner.equals(System.getProperty("user.name")) && !owner.equals("root")) throw new IllegalStateException("Untrusted credential owner");
            String secret=Files.readString(absolute).strip();
            if (!secret.matches("[A-Za-z0-9_-]{43,128}")) throw new IllegalStateException("Invalid management credential format");
            return secret;
        } catch (IOException e) { throw new IllegalStateException("Management credential is unavailable"); }
    }
    @Override public void doFilter(ServletRequest input,ServletResponse output,FilterChain chain) throws IOException,ServletException {
        var request=(HttpServletRequest)input;var response=(HttpServletResponse)output;
        try {
            if (!com.sitionix.forgeagent.api.security.AgentManagementRoutePolicy.remoteAccess(request)) { chain.doFilter(input,output);return; }
        } catch (IllegalArgumentException invalid) { response.setStatus(401);return; }
        response.setHeader("Cache-Control","no-store");
        String authorization=request.getHeader("Authorization");
        boolean valid=java.util.Collections.list(request.getHeaders("Authorization")).size()==1 && authorization!=null && authorization.startsWith("Bearer ")
                && MessageDigest.isEqual(credential,authorization.substring(7).getBytes(StandardCharsets.UTF_8));
        if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress() || !valid) {
            response.setStatus(401);response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"REMOTE_ACCESS_UNAUTHORIZED\",\"message\":\"Local service authentication required\",\"correlationId\":\""+java.util.UUID.randomUUID()+"\"}");
            return;
        }
        chain.doFilter(request,response);
    }
}
