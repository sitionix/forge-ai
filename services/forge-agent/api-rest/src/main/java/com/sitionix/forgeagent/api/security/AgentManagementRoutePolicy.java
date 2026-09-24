package com.sitionix.forgeagent.api.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.RequestPath;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Uses the current dispatch target, including servlet include targets. */
public final class AgentManagementRoutePolicy {
    private static final PathPattern REMOTE=PathPatternParser.defaultInstance.parse("/api/v1/remote-access/{*path}");
    private AgentManagementRoutePolicy() { }
    public static String path(HttpServletRequest request) {
        String uri=request.getRequestURI();
        if (request.getDispatcherType()==DispatcherType.INCLUDE) {
            Object included=request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI);
            if (!(included instanceof String)) throw new IllegalArgumentException("Invalid management target");
            uri=(String)included;
        }
        RequestPath.parse(uri,request.getContextPath());
        return uri.substring(request.getContextPath().length());
    }
    public static boolean remoteAccess(HttpServletRequest request) {
        return REMOTE.matches(RequestPath.parse(path(request),"").pathWithinApplication());
    }
}
