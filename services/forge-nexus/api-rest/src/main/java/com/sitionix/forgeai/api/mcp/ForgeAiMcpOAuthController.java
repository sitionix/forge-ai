package com.sitionix.forgeai.api.mcp;

import com.sitionix.forgeai.domain.model.mcp.*;
import com.sitionix.forgeai.domain.usecase.ManageAgentMcpConnections;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/infrastructure/agents/integrations/mcp")
public class ForgeAiMcpOAuthController {
    private static final String COOKIE_PATH="/fgaisox/api/v1/infrastructure/agents/integrations/mcp";
    private final ManageAgentMcpConnections service;
    private final McpOAuthBrowserProperties properties;
    public ForgeAiMcpOAuthController(ManageAgentMcpConnections service,McpOAuthBrowserProperties properties){this.service=service;this.properties=properties;}
    @PostMapping("/connections/{id}/oauth/start")
    public McpOAuthStart start(@PathVariable UUID id,@RequestBody Map<String,Object> body,HttpServletRequest request,HttpServletResponse response){
        requireBrowser(body,request);
        String binding=UUID.randomUUID().toString()+UUID.randomUUID();
        var start=service.startOAuth(id,binding);
        response.addHeader(HttpHeaders.SET_COOKIE,cookie(start.transactionId(),binding,request,properties.transactionTtl()).toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        return start;
    }
    @DeleteMapping("/connections/{id}/oauth/transactions/{transactionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id,@PathVariable UUID transactionId,@RequestBody Map<String,Object> body,
            HttpServletRequest request,HttpServletResponse response){
        requireBrowser(body,request);String binding=binding(request,transactionId);
        service.cancelOAuth(id,transactionId,binding);
        clear(transactionId,request,response);
    }
    @GetMapping("/oauth/callback")
    public ResponseEntity<Void> callback(HttpServletRequest request,HttpServletResponse response){
        if(!request.getRequestURI().equals(request.getContextPath()+McpOAuthBrowserResult.CALLBACK))throw new McpOAuthBrowserDeniedException();
        UUID transaction=McpOAuthBrowserResult.transaction(parameter(request,"state"));
        try {
            var callback=new McpOAuthCallback(parameter(request,"state"),binding(request,transaction),parameter(request,"code"),
                    parameter(request,"error"),parameter(request,"iss"));
            var completed=service.completeOAuth(callback);
            return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.CACHE_CONTROL,"no-store")
                    .header("Referrer-Policy","no-referrer").location(McpOAuthBrowserResult.location(transaction,completed.connectionId())).build();
        } finally {clear(transaction,request,response);}
    }
    private void requireBrowser(Map<String,Object> body,HttpServletRequest request){
        if(!properties.browserOrigin().toString().equals(request.getHeader(HttpHeaders.ORIGIN))
                || request.getContentType()==null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType())))
            throw new McpOAuthBrowserDeniedException();
        if(body==null || !body.isEmpty())throw new IllegalArgumentException("Invalid OAuth request");
    }
    private static String parameter(HttpServletRequest request,String name){
        var values=request.getParameterValues(name);
        if(values==null)return null;
        if(values.length!=1)throw new IllegalArgumentException("Invalid OAuth request");
        return values[0];
    }
    private static String binding(HttpServletRequest request,UUID transaction){
        var cookies=request.getCookies();
        var matches=cookies==null?List.<Cookie>of():Arrays.stream(cookies).filter(c->c.getName().equals("ForgeMcpOAuth-"+transaction)).toList();
        if(matches.size()!=1 || !matches.get(0).getValue().matches("[A-Za-z0-9_-]{32,128}"))throw new McpOAuthBrowserDeniedException();
        return matches.get(0).getValue();
    }
    private static ResponseCookie cookie(UUID transaction,String binding,HttpServletRequest request,java.time.Duration age){
        return ResponseCookie.from("ForgeMcpOAuth-"+transaction,binding).httpOnly(true).sameSite("Lax").secure(request.isSecure())
                .path(COOKIE_PATH).maxAge(age).build();
    }
    private static void clear(UUID transaction,HttpServletRequest request,HttpServletResponse response){
        response.addHeader(HttpHeaders.SET_COOKIE,cookie(transaction,"",request,java.time.Duration.ZERO).toString());
    }
}
