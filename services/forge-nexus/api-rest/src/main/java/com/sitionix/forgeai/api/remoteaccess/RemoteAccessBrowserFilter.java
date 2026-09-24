package com.sitionix.forgeai.api.remoteaccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
final class RemoteAccessBrowserFilter extends OncePerRequestFilter {
    private final RemoteAccessOperatorAuthentication authentication;
    private final ObjectMapper mapper;
    RemoteAccessBrowserFilter(RemoteAccessOperatorAuthentication authentication,ObjectMapper mapper) { this.authentication=authentication;this.mapper=mapper; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        response.setHeader("Cache-Control","no-store");
        String origin=request.getHeader("Origin"),host=request.getHeader("Host"),site=request.getHeader("Sec-Fetch-Site");
        boolean unsafe=!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod());
        boolean allowed=InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()
            && authentication.origin().getRawAuthority().equals(host)
            && (origin==null ? !unsafe : authentication.origin().toString().equals(origin))
            && (site==null || site.equals("same-origin") || site.equals("none"));
        if (!allowed) {
            response.setStatus(403);response.setContentType("application/json");
            mapper.writeValue(response.getOutputStream(),new RemoteAccessProxyDtos.Error("REMOTE_ACCESS_FORBIDDEN","Local operator origin required",UUID.randomUUID().toString()));return;
        }
        var session=request.getSession(false);
        if (authentication.expired(session)) { session.invalidate();SecurityContextHolder.clearContext(); }
        chain.doFilter(request,response);
    }
}
