package com.sitionix.forgeagent.api.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Collections;

/** Enabled-mode guard for every Agent control request, including redispatches. */
public final class AgentManagementAuthenticationFilter implements Filter {
    private final ProtectedCredentialFile credential;
    public AgentManagementAuthenticationFilter(ProtectedCredentialFile credential) { this.credential = credential; }
    @Override public void doFilter(ServletRequest input,ServletResponse output,FilterChain chain) throws IOException,ServletException {
        HttpServletRequest request = (HttpServletRequest)input;
        HttpServletResponse response = (HttpServletResponse)output;
        String uri=request.getRequestURI(), context=request.getContextPath();
        if (!uri.startsWith(context)) { response.sendError(403,"Forbidden"); return; }
        String path = uri.substring(context.length());
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
