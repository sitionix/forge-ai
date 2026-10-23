package com.sitionix.forgeagent.api.security;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.http.server.RequestPath;
public final class StandaloneSessionServiceFilter implements Filter {
 private final byte[] secret;
 public StandaloneSessionServiceFilter(String secret){this.secret=secret.getBytes(StandardCharsets.UTF_8);}
 @Override public void doFilter(ServletRequest input,ServletResponse output,FilterChain chain)throws IOException,ServletException {
  var request=(HttpServletRequest)input;var response=(HttpServletResponse)output;
  String path=AgentManagementRoutePolicy.path(request);
  boolean matches=PathPatternParser.defaultInstance.parse("/api/v1/projects/{projectId}/agent-sessions/{*rest}").matches(RequestPath.parse(path,"").pathWithinApplication());
  if(!matches){chain.doFilter(input,output);return;}
  response.setHeader("Cache-Control","no-store");
  String auth=request.getHeader("Authorization");
  boolean browser=Collections.list(request.getHeaderNames()).stream().anyMatch(n->n.equalsIgnoreCase("Origin")||n.toLowerCase(Locale.ROOT).startsWith("sec-fetch-"));
  if(path.contains("%")||path.contains(";")||path.contains("\\")||browser||!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()
      ||Collections.list(request.getHeaders("Authorization")).size()!=1||auth==null||!auth.startsWith("Bearer ")||!MessageDigest.isEqual(secret,auth.substring(7).getBytes(StandardCharsets.UTF_8))) {
   response.setStatus(403);response.setContentType("application/json");response.getWriter().write("{\"code\":\"SESSION_SERVICE_DENIED\",\"message\":\"Local service authentication required.\"}");return;
  }
  if(request.getContentLengthLong()>262144){response.setStatus(413);return;}
  request.setAttribute("forgeStandaloneServiceAuthenticated",Boolean.TRUE);
  chain.doFilter(new HttpServletRequestWrapper(request){
   @Override public ServletInputStream getInputStream()throws IOException {
    var stream=super.getInputStream();return new ServletInputStream(){int count;
     public boolean isFinished(){return stream.isFinished();}public boolean isReady(){return stream.isReady();}public void setReadListener(ReadListener listener){stream.setReadListener(listener);}
     public int read()throws IOException{int b=stream.read();if(b>=0&&++count>262144)throw new IOException("Request body limit exceeded");return b;}
    };
   }
  },response);
 }
}
