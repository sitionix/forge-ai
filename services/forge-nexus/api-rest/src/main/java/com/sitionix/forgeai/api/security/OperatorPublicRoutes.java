package com.sitionix.forgeai.api.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;

/** Current dispatch target for the scoped Remote Access guard. */
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
}
