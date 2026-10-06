package com.sitionix.forgeai.api.llm;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LlmAuthorizationBrowserFilter extends OncePerRequestFilter {
    private final LlmAuthorizationBrowserGuard guard;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    public LlmAuthorizationBrowserFilter(LlmAuthorizationBrowserGuard guard,com.fasterxml.jackson.databind.ObjectMapper mapper){this.guard=guard;this.mapper=mapper;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){String path=org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request);return !(path.equals(ForgeAiLlmAuthorizationController.PREFIX) || path.startsWith(ForgeAiLlmAuthorizationController.PREFIX+"/"));}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException {
        request.setAttribute("forgeSensitiveRequest",Boolean.TRUE);response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");
        try {
            if(!Set.of("GET","POST","DELETE").contains(request.getMethod())){error(response,405,"INVALID_REQUEST");return;}
            boolean bootstrap="GET".equals(request.getMethod()) && request.getRequestURI().equals(request.getContextPath()+ForgeAiLlmAuthorizationController.PREFIX+"/providers");
            guard.require(request,response,bootstrap);
        } catch(LlmAuthorizationBrowserGuard.Denied denied){error(response,403,"LLM_BROWSER_DENIED");return;}
        catch(com.sitionix.forgeai.domain.model.llm.LlmAuthorizationFailure unavailable){error(response,503,"CODEX_AUTH_UNAVAILABLE");return;}
        // Reject syntax/unknown fields before framework diagnostic logging can render attacker text.
        byte[] content=null;
        if(!"GET".equals(request.getMethod())) {
            content=request.getInputStream().readNBytes(4097);
            try {
                var parsed=mapper.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(content);
                if(content.length>4096 || parsed==null || !parsed.isObject() || !parsed.isEmpty()){error(response,400,"INVALID_REQUEST");return;}
            } catch(Exception invalid){error(response,400,"INVALID_REQUEST");return;}
        }
        final byte[] cached=content;
        chain.doFilter(new HttpServletRequestWrapper(request){
            @Override public ServletInputStream getInputStream()throws IOException {return cached==null?super.getInputStream():stream(cached);}
            @Override public String getQueryString(){return null;}
            @Override public Map<String,String[]> getParameterMap(){return Map.of();}
            @Override public Enumeration<String> getHeaderNames(){return Collections.enumeration(Collections.list(super.getHeaderNames()).stream().filter(name->!"cookie".equalsIgnoreCase(name)).toList());}
        },response);
    }
    private static ServletInputStream stream(byte[] content){var input=new java.io.ByteArrayInputStream(content);return new ServletInputStream(){
        public int read(){return input.read();}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}
        public void setReadListener(ReadListener listener){throw new IllegalStateException("Synchronous authorization request");}
    };}
    private static void error(HttpServletResponse response,int status,String code)throws IOException {response.setStatus(status);response.setContentType("application/json");response.getWriter().write("{\"code\":\""+code+"\",\"message\":\"LLM authorization request rejected.\",\"correlationId\":null}");}
}
