package com.sitionix.forgeagent.api.llm;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class ForgeCodexServiceAuthFilter extends OncePerRequestFilter {
    static final String ALLOWED="forgeCodexServiceAllowed";
    private final Path tokenFile;
    public ForgeCodexServiceAuthFilter(@Value("${FORGE_CODEX_SERVICE_TOKEN_FILE:/etc/forge-ai/codex-service/token}") String tokenFile) {
        this.tokenFile=Path.of(tokenFile);
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        var path=org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return !(path.equals(ForgeCodexInternalController.PREFIX) || path.startsWith(ForgeCodexInternalController.PREFIX+"/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        request.setAttribute("forgeSensitiveRequest",Boolean.TRUE);
        response.setHeader("Cache-Control","no-store");
        boolean browser=Collections.list(request.getHeaderNames()).stream().anyMatch(x->x.equalsIgnoreCase("Origin") || x.toLowerCase(Locale.ROOT).startsWith("sec-fetch-"));
        if(browser){reject(response,403);return;}
        try {
            var info=Files.readAttributes(tokenFile,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!info.isRegularFile() || !info.permissions().equals(PosixFilePermissions.fromString("rw-------"))
                    || !info.owner().equals(Files.getOwner(Path.of("/proc/self"))) || info.size()<32 || info.size()>256
                    || ((Number)Files.getAttribute(tokenFile,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()!=1) throw new IOException();
            final String token;
            try(var channel=Files.newByteChannel(tokenFile,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))){
                var bytes=java.nio.ByteBuffer.allocate(257);while(bytes.hasRemaining() && channel.read(bytes)>0){}
                bytes.flip();token=StandardCharsets.US_ASCII.decode(bytes).toString();
            }
            if(!token.matches("[A-Za-z0-9_-]{32,256}"))throw new IOException();
            String expected="Bearer "+token;
            String supplied=request.getHeader("Authorization");
            if(supplied==null || supplied.length()>263 || Collections.list(request.getHeaders("Authorization")).size()!=1
                    || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII))) {reject(response,401);return;}
        } catch(Exception unavailable){reject(response,401);return;}
        request.setAttribute(ALLOWED,Boolean.TRUE);
        chain.doFilter(request,response);
    }
    private static void reject(HttpServletResponse response,int status)throws IOException {
        response.setStatus(status);response.setContentType("application/json");response.getWriter().write("{\"code\":\"CODEX_SERVICE_DENIED\"}");
    }
}
