package com.sitionix.forgeai.api.remoteaccess;
import jakarta.servlet.http.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/** Local bootstrap authentication only; not a Remote Access lifecycle authority. */
public final class RemoteAccessOperatorAuthentication {
    public static final String AUTHENTICATED_AT="forge.remote-access.operator-authenticated-at";
    private final byte[] secret;
    private final URI origin;
    private final Clock clock;
    private long windowStart;
    private int attempts;
    public RemoteAccessOperatorAuthentication(byte[] secret,URI origin,Clock clock) {
        if (secret.length<43 || origin.getHost()==null || origin.getUserInfo()!=null || origin.getQuery()!=null
                || origin.getFragment()!=null || !("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))
                || !(origin.getPath().isEmpty()) || origin.getPort()<1) throw new IllegalArgumentException("Explicit operator origin required");
        try {
            if (!java.util.Arrays.stream(java.net.InetAddress.getAllByName(origin.getHost())).allMatch(java.net.InetAddress::isLoopbackAddress))
                throw new IllegalArgumentException("Loopback operator origin required");
        } catch (java.net.UnknownHostException e) { throw new IllegalArgumentException("Invalid operator origin"); }
        this.secret=secret.clone();this.origin=origin;this.clock=clock;
    }
    public URI origin() { return origin; }
    public boolean expired(HttpSession session) {
        if (session==null) return false;
        var time=session.getAttribute(AUTHENTICATED_AT);
        return !(time instanceof Long value) || clock.millis()-value>=900_000;
    }
    public synchronized void login(String supplied,HttpServletRequest request,HttpServletResponse response) {
        long now=clock.millis();
        if (now-windowStart>=60_000) { attempts=0;windowStart=now; }
        if (++attempts>10 || supplied==null || !MessageDigest.isEqual(secret,supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new BadCredentialsException("Operator authentication failed");
        }
        var previous=request.getSession(false);if (previous!=null) previous.invalidate();
        var session=request.getSession(true);session.setMaxInactiveInterval(900);session.setAttribute(AUTHENTICATED_AT,now);
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("remote-access-operator",null,List.of(new SimpleGrantedAuthority("ROLE_REMOTE_ACCESS_OPERATOR"))));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context,request,response);
    }
}
