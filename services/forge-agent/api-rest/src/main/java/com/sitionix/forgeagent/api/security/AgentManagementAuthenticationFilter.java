package com.sitionix.forgeagent.api.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Collections;

/** Enabled-mode guard for every Agent control request, including redispatches. */
public final class AgentManagementAuthenticationFilter implements Filter {
    private final ProtectedCredentialFile credential;
    private final boolean remoteAccessOwned;
    public AgentManagementAuthenticationFilter(ProtectedCredentialFile credential) { this(credential,false); }
    public AgentManagementAuthenticationFilter(ProtectedCredentialFile credential,boolean remoteAccessOwned) { this.credential=credential;this.remoteAccessOwned=remoteAccessOwned; }
    @Override public void doFilter(ServletRequest input,ServletResponse output,FilterChain chain) throws IOException,ServletException {
        HttpServletRequest request = (HttpServletRequest)input;
        HttpServletResponse response = (HttpServletResponse)output;
        String path;
        try { path=AgentManagementRoutePolicy.path(request);
            // Container error rendering is a terminal response, never a controller bypass.
            // In particular an RA failure must not become a second-audience 401 at /error.
            if (request.getDispatcherType()==DispatcherType.ERROR && path.equals("/error")
                    && request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer status && status>=400 && status<=599) {
                response.setHeader("Cache-Control","no-store");response.setStatus(status);return;
            }
            if (remoteAccessOwned && AgentManagementRoutePolicy.remoteAccess(request)) { chain.doFilter(input,output);return; }
            if (AgentManagementRoutePolicy.runtimeMcp(request)) { chain.doFilter(input,output);return; }
        } catch (IllegalArgumentException invalid) { response.setStatus(403);return; }
        if ((request.getMethod().equals("GET") || request.getMethod().equals("HEAD")) && publicStatic(path)) {
            chain.doFilter(input,output); return;
        }
        response.setHeader("Cache-Control","no-store");
        if (publicHealth(path)) {
            chain.doFilter(input,output); return;
        }
        var values = Collections.list(request.getHeaders("Authorization"));
        if (values.size() != 1 || !credential.matchesBearer(values.getFirst())) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,"Unauthorized"); return;
        }
        chain.doFilter(input,output);
    }
    private static boolean publicStatic(String path) {
        if (path.contains("%") || path.contains(";") || path.contains("\\") || path.contains("//")
                || path.contains("/./") || path.contains("/../") || path.endsWith("/.") || path.endsWith("/.."))
            return false;
        return path.equals("/") || path.equals("/index.html") || path.equals("/favicon.ico")
                || path.equals("/manifest.webmanifest") || path.equals("/robots.txt")
                || (path.matches("/(assets|static)/[A-Za-z0-9._/-]+") && !path.endsWith("/"));
    }
    private static boolean publicHealth(String path) {
        return path.equals("/actuator/info") || path.matches("/actuator/health(/[A-Za-z0-9_-]+)*");
    }
}
