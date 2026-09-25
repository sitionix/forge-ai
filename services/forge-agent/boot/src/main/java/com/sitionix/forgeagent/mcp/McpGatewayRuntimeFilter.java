package com.sitionix.forgeagent.mcp;

import com.sitionix.forgeagent.api.security.AgentManagementRoutePolicy;
import com.sitionix.forgeagent.application.mcp.McpGatewayAccessException;
import com.sitionix.forgeagent.domain.port.McpGatewayRuntime;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Owns only the exact runtime MCP route; the management guard owns every other route. */
public final class McpGatewayRuntimeFilter implements Filter {
    public static final String TOKEN_ATTRIBUTE = McpGatewayRuntimeFilter.class.getName() + ".token";
    private final McpGatewayRuntime runtime;
    private final Set<String> allowedHosts;

    public McpGatewayRuntimeFilter(McpGatewayRuntime runtime, Set<String> allowedHosts) {
        if (allowedHosts == null || allowedHosts.isEmpty() || allowedHosts.contains("*"))
            throw new IllegalArgumentException("MCP gateway allowed hosts required");
        this.runtime = runtime;
        this.allowedHosts = Set.copyOf(allowedHosts);
    }

    @Override public void doFilter(ServletRequest input, ServletResponse output, FilterChain chain)
            throws IOException, ServletException {
        var request = (HttpServletRequest) input;
        var response = (HttpServletResponse) output;
        String path;
        try {
            path = AgentManagementRoutePolicy.path(request);
            if (!AgentManagementRoutePolicy.runtimeMcp(request)) { chain.doFilter(input, output); return; }
        } catch (IllegalArgumentException invalid) {
            response.setStatus(403);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        String host = singleHeader(request, "Host");
        var origins = Collections.list(request.getHeaders("Origin"));
        if (!validHost(host) || origins.size() > 1
                || !validOrigin(origins.isEmpty() ? null : origins.getFirst(), host)) {
            response.setStatus(403);
            return;
        }
        String authorization = singleHeader(request, "Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.substring(7).isBlank()) {
            response.setStatus(401);
            return;
        }
        UUID connectionId;
        String token = authorization.substring(7);
        try {
            connectionId = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
            runtime.authorize(token, connectionId);
        } catch (McpGatewayAccessException | IllegalArgumentException denial) {
            response.setStatus(401);
            return;
        } catch (RuntimeException unavailable) {
            response.setStatus(503);
            return;
        }
        request.setAttribute(TOKEN_ATTRIBUTE, token);
        chain.doFilter(input, output);
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        return values.size() == 1 ? values.getFirst() : null;
    }

    private boolean validHost(String hostHeader) {
        if (hostHeader == null) return false;
        try {
            URI uri = URI.create("http://" + hostHeader);
            return uri.getHost() != null && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private boolean validOrigin(String origin, String hostHeader) {
        if (origin == null) return true;
        try {
            URI source = URI.create(origin);
            URI target = URI.create("http://" + hostHeader);
            int sourcePort = source.getPort() < 0 ? ("https".equals(source.getScheme()) ? 443 : 80) : source.getPort();
            int targetPort = target.getPort() < 0 ? 80 : target.getPort();
            return ("http".equals(source.getScheme()) || "https".equals(source.getScheme()))
                    && source.getHost() != null && source.getHost().equalsIgnoreCase(target.getHost())
                    && sourcePort == targetPort && source.getRawUserInfo() == null
                    && (source.getRawPath() == null || source.getRawPath().isEmpty())
                    && source.getRawQuery() == null && source.getRawFragment() == null;
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
