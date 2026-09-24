package com.sitionix.forgeai.api.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;

/** Narrow canonical public routes, shared by both operator authentication modes. */
public final class OperatorPublicRoutes {
    private OperatorPublicRoutes() { }
    public static String path(HttpServletRequest request) {
        String uri=request.getRequestURI();
        if (request.getDispatcherType()==DispatcherType.INCLUDE) {
            Object included=request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI);
            if (!(included instanceof String)) return "";
            uri=(String)included;
        }
        String context=request.getContextPath();
        return uri.startsWith(context)?uri.substring(context.length()):"";
    }
    public static boolean matches(HttpServletRequest request) {
        String path=path(request);
        return publicHealth(path) || ((request.getMethod().equals("GET") || request.getMethod().equals("HEAD")) && publicStatic(path));
    }
    public static boolean publicHealth(String path) {
        return path.equals("/actuator/info") || path.matches("/actuator/health(/[A-Za-z0-9_-]+)*");
    }
    public static boolean publicStatic(String path) {
        if (path.contains("%") || path.contains(";") || path.contains("\\") || path.contains("//")
                || path.contains("/./") || path.contains("/../") || path.endsWith("/.") || path.endsWith("/.."))
            return false;
        return path.equals("/") || path.equals("/index.html") || path.equals("/favicon.ico")
                || path.equals("/manifest.webmanifest") || path.equals("/robots.txt")
                || (path.matches("/(assets|static)/[A-Za-z0-9._/-]+") && !path.endsWith("/"));
    }
}
