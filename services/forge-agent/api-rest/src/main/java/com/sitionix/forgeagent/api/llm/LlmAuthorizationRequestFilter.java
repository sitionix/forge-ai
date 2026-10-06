package com.sitionix.forgeagent.api.llm;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Agent auth routes are typed Nexus-to-Agent calls, not browser routes. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class LlmAuthorizationRequestFilter extends OncePerRequestFilter {
    public static final String ALLOWED="forgeAgentLlmRequestAllowed";
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    public LlmAuthorizationRequestFilter(com.fasterxml.jackson.databind.ObjectMapper mapper){this.mapper=mapper;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){String path=org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request);return !(path.equals(LlmAuthorizationController.PREFIX) || path.startsWith(LlmAuthorizationController.PREFIX+"/"));}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        request.setAttribute("forgeSensitiveRequest",Boolean.TRUE);response.setHeader("Cache-Control","no-store");
        boolean browser=Collections.list(request.getHeaderNames()).stream().anyMatch(name->"origin".equalsIgnoreCase(name) || name.toLowerCase(Locale.ROOT).startsWith("sec-fetch-"));
        boolean allowed=Set.of("GET","POST","DELETE").contains(request.getMethod());
        if(browser || !allowed || !"GET".equals(request.getMethod()) && !json(request.getContentType())) {
            error(response,allowed?403:405,"LLM_BROWSER_DENIED");return;
        }
        byte[] content=null;
        if(!"GET".equals(request.getMethod())) {
            content=request.getInputStream().readNBytes(4097);
            try {
                var parsed=mapper.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(content);
                boolean logout=org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request).equals(LlmAuthorizationController.PREFIX+"/codex/logout");
                if(content.length>4096 || parsed==null || !parsed.isObject() || logout && !parsed.isEmpty()
                        || !logout && (parsed.size()>1 || parsed.size()==1 && (!parsed.has("browserBinding") || !parsed.get("browserBinding").isTextual()))) {
                    error(response,400,"INVALID_REQUEST");return;
                }
            } catch(Exception invalid){error(response,400,"INVALID_REQUEST");return;}
        }
        request.setAttribute(ALLOWED,Boolean.TRUE);
        final byte[] cached=content;
        chain.doFilter(new HttpServletRequestWrapper(request){
            @Override public ServletInputStream getInputStream()throws IOException {return cached==null?super.getInputStream():stream(cached);}
            @Override public String getQueryString(){return null;}
            @Override public Map<String,String[]> getParameterMap(){return Map.of();}
            @Override public Enumeration<String> getHeaderNames(){return Collections.enumeration(Collections.list(super.getHeaderNames()).stream().filter(name->!"X-Forge-Browser-Binding".equalsIgnoreCase(name) && !"Cookie".equalsIgnoreCase(name)).toList());}
        },response);
    }
    private static ServletInputStream stream(byte[] content){var input=new java.io.ByteArrayInputStream(content);return new ServletInputStream(){
        public int read(){return input.read();}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}
        public void setReadListener(ReadListener listener){throw new IllegalStateException("Synchronous authorization request");}
    };}
    private static boolean json(String type){try{if(type==null)return false;var media=MediaType.parseMediaType(type);return "application".equals(media.getType()) && "json".equals(media.getSubtype());}catch(RuntimeException failure){return false;}}
    private static void error(HttpServletResponse response,int status,String code)throws IOException{response.setStatus(status);response.setContentType("application/json");response.getWriter().write("{\"code\":\""+code+"\",\"message\":\"LLM authorization request rejected.\",\"correlationId\":null}");}
}
