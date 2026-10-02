package com.sitionix.forgeai.api.mcp;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/** Keep callback parameters usable by its controller, but absent from framework URL/parameter logging. */
public class McpOAuthCallbackLoggingFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        request.setAttribute("forgeSensitiveRequest",Boolean.TRUE);
        response.setHeader("Referrer-Policy","no-referrer");
        chain.doFilter(new HttpServletRequestWrapper(request){
            @Override public String getQueryString(){return null;}
            @Override public Map<String,String[]> getParameterMap(){return Map.of();}
        },response);
    }
}
