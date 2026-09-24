package com.sitionix.forgeai.api.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/** One guard for all Nexus control routes and every servlet redispatch. */
public final class OperatorManagementAuthenticationFilter implements Filter {
    public static final String COOKIE="FG_SESSION";
    public static final String CSRF="X-Forge-CSRF";
    private static final String SESSION_PATH="/api/v1/operator/session";
    private final OperatorSessionService sessions;
    private final URI origin;
    private final String host;
    public OperatorManagementAuthenticationFilter(OperatorSessionService sessions,URI origin) {
        if (sessions==null || origin==null || origin.getHost()==null || origin.getPort()==0
                || origin.getRawUserInfo()!=null || origin.getRawQuery()!=null || origin.getRawFragment()!=null
                || !(origin.getRawPath()==null || origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))
                || !("https".equalsIgnoreCase(origin.getScheme()) || ("http".equalsIgnoreCase(origin.getScheme()) && loopback(origin.getHost()))))
            throw new IllegalArgumentException("Invalid operator origin");
        this.sessions=sessions; this.origin=canonical(origin); this.host=this.origin.getRawAuthority();
    }
    public URI origin() { return origin; }
    @Override public void doFilter(ServletRequest input,ServletResponse output,FilterChain chain) throws IOException,ServletException {
        HttpServletRequest request=(HttpServletRequest)input;
        HttpServletResponse response=(HttpServletResponse)output;
        response.setHeader("Cache-Control","no-store");
        String uri=request.getRequestURI(), context=request.getContextPath();
        if (!uri.startsWith(context)) { response.sendError(403,"Forbidden"); return; }
        String path=uri.substring(context.length());
        if ((request.getMethod().equals("GET") || request.getMethod().equals("HEAD")) && publicStatic(path)) {
            chain.doFilter(input,output); return;
        }
        if (publicHealth(path)) {
            chain.doFilter(input,output); return;
        }
        if (!safeHost(request) || !safeOrigin(request)) { response.sendError(403,"Forbidden"); return; }
        boolean sessionRoute=path.equals(SESSION_PATH);
        boolean login=sessionRoute && request.getMethod().equals("POST");
        if (login) { chain.doFilter(input,output); return; }
        String id=cookie(request);
        var session=sessions.find(id);
        if (session.isEmpty()) { response.sendError(401,"Unauthorized"); return; }
        if (!safeMethod(request.getMethod())) {
            var csrf=Collections.list(request.getHeaders(CSRF));
            if (csrf.size()!=1 || !sessions.csrfMatches(session.orElseThrow(),csrf.getFirst())) {
                response.sendError(403,"Forbidden"); return;
            }
        }
        chain.doFilter(input,output);
    }
    private boolean safeHost(HttpServletRequest request) {
        var hosts=Collections.list(request.getHeaders("Host"));
        if (hosts.size()!=1 || !host.equals(hosts.getFirst())) return false;
        var names=Collections.list(request.getHeaderNames());
        return names.stream().noneMatch(name -> {
            String lower=name.toLowerCase(Locale.ROOT);
            return lower.equals("forwarded") || lower.startsWith("x-forwarded-");
        });
    }
    private boolean safeOrigin(HttpServletRequest request) {
        var values=Collections.list(request.getHeaders("Origin"));
        if (values.size()>1 || (!values.isEmpty() && !origin.toString().equals(values.getFirst()))) return false;
        String fetchSite=request.getHeader("Sec-Fetch-Site");
        if (fetchSite!=null && !fetchSite.equals("same-origin") && !fetchSite.equals("none")) return false;
        return safeMethod(request.getMethod()) || values.size()==1;
    }
    private static boolean safeMethod(String method) { return method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS"); }
    private static boolean publicHealth(String path) {
        return path.equals("/actuator/info") || path.matches("/actuator/health(/[A-Za-z0-9_-]+)*");
    }
    private static boolean publicStatic(String path) {
        if (path.contains("%") || path.contains(";") || path.contains("\\") || path.contains("//")
                || path.contains("/./") || path.contains("/../") || path.endsWith("/.") || path.endsWith("/.."))
            return false;
        return path.equals("/") || path.equals("/index.html") || path.equals("/favicon.ico")
                || path.equals("/manifest.webmanifest") || path.equals("/robots.txt")
                || (path.matches("/(assets|static)/[A-Za-z0-9._/-]+") && !path.endsWith("/"));
    }
    private static boolean loopback(String host) { return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]"); }
    private static URI canonical(URI value) {
        String scheme=value.getScheme().toLowerCase(Locale.ROOT);
        int port=value.getPort();
        if (port == (scheme.equals("https") ? 443 : 80)) port=-1;
        try { return new URI(scheme,null,value.getHost().toLowerCase(Locale.ROOT),port,null,null,null); }
        catch (URISyntaxException exception) { throw new IllegalArgumentException("Invalid operator origin"); }
    }
    static String cookie(HttpServletRequest request) {
        String found=null;
        Cookie[] cookies=request.getCookies();
        if (cookies==null) return null;
        for (Cookie value:cookies) if (COOKIE.equals(value.getName())) {
            if (found!=null) return null;
            found=value.getValue();
        }
        return found;
    }
}
