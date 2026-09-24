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
final class RemoteAccessBrowserFilter implements Filter {
    private final RemoteAccessOperatorAuthentication authentication;
    private final ObjectMapper mapper;
    private final boolean combined;
    RemoteAccessBrowserFilter(RemoteAccessOperatorAuthentication authentication,ObjectMapper mapper) { this(authentication,mapper,false); }
    RemoteAccessBrowserFilter(RemoteAccessOperatorAuthentication authentication,ObjectMapper mapper,boolean combined) { this.authentication=authentication;this.mapper=mapper;this.combined=combined; }
    @Override public void doFilter(ServletRequest input,ServletResponse output,FilterChain chain) throws IOException,ServletException {
        var request=(HttpServletRequest)input;var response=(HttpServletResponse)output;
        if (combined && com.sitionix.forgeai.api.security.OperatorPublicRoutes.matches(request)) { chain.doFilter(input,output);return; }
        response.setHeader("Cache-Control","no-store");
        if (combined && request.getDispatcherType()==DispatcherType.ERROR
                && com.sitionix.forgeai.api.security.OperatorPublicRoutes.path(request).equals("/error")
                && request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer status && status>=400 && status<=599) {
            response.setStatus(status);return;
        }
        String origin=request.getHeader("Origin"),host=request.getHeader("Host"),site=request.getHeader("Sec-Fetch-Site");
        boolean unsafe=!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod());
        boolean headers=java.util.Collections.list(request.getHeaders("Host")).size()==1
            && java.util.Collections.list(request.getHeaders("Origin")).size()<=1
            && java.util.Collections.list(request.getHeaders("X-CSRF-TOKEN")).size()<=1
            && java.util.Collections.list(request.getHeaderNames()).stream().noneMatch(name -> name.equalsIgnoreCase("Forwarded") || name.toLowerCase(java.util.Locale.ROOT).startsWith("x-forwarded-"));
        boolean allowed=headers && InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()
            && authentication.origin().getRawAuthority().equals(host)
            && (origin==null ? !unsafe : authentication.origin().toString().equals(origin))
            && (site==null || site.equals("same-origin") || site.equals("none"));
        if (!allowed) {
            response.setStatus(403);response.setContentType("application/json");
            mapper.writeValue(response.getOutputStream(),new RemoteAccessProxyDtos.Error("REMOTE_ACCESS_FORBIDDEN","Local operator origin required",UUID.randomUUID().toString()));return;
        }
        var session=request.getSession(false);
        if (authentication.expired(session)) { session.invalidate();SecurityContextHolder.clearContext(); }
        // CsrfFilter is once-per-request; nested dispatches must check the current target too.
        if (request.getDispatcherType()!=DispatcherType.REQUEST && unsafe
                && !com.sitionix.forgeai.api.security.OperatorPublicRoutes.path(request).equals("/api/v1/infrastructure/agents/remote-access/operator/login")) {
            var token=new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository().loadToken(request);
            var values=java.util.Collections.list(request.getHeaders("X-CSRF-TOKEN"));
            if (token==null || values.size()!=1 || !java.security.MessageDigest.isEqual(token.getToken().getBytes(java.nio.charset.StandardCharsets.UTF_8),values.getFirst().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                response.setStatus(403);return;
            }
        }
        chain.doFilter(request,response);
    }
}
