package com.sitionix.forgeagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Disposable pre-registered AS, never live provider compatibility evidence. */
final class McpOAuthProviderFixture implements AutoCloseable {
    static final String CLIENT="forge-fixture",SECRET="registered-client-secret-canary",ACCESS="oauth-access-canary",REFRESH="oauth-refresh-canary";
    final HttpServer server;
    final String callback,resource;
    final List<String> operations=Collections.synchronizedList(new ArrayList<>());
    final List<String> sensitive=Collections.synchronizedList(new ArrayList<>());
    private final Map<String,String> challenges=new HashMap<>();
    private final ObjectMapper json=new ObjectMapper();
    McpOAuthProviderFixture(String callback,String resource) throws Exception {
        this.callback=callback;this.resource=resource;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/authorize",this::authorize);server.createContext("/consent",this::consent);
        server.createContext("/token",this::token);server.createContext("/revoke",exchange->{operations.add("revoke");exchange.sendResponseHeaders(200,-1);exchange.close();});
        server.start();
    }
    String issuer(){return "http://localhost:"+server.getAddress().getPort();}
    private void authorize(HttpExchange exchange) throws java.io.IOException {
        var p=parameters(exchange.getRequestURI().getRawQuery());
        if(!CLIENT.equals(p.get("client_id")) || !callback.equals(p.get("redirect_uri")) || !resource.equals(p.get("resource"))
                || !"S256".equals(p.get("code_challenge_method"))) {reply(exchange,400,Map.of("error","invalid_request"));return;}
        sensitive.add(p.get("state"));
        String hidden=p.entrySet().stream().map(e->"<input type='hidden' name='"+e.getKey()+"' value='"+escape(e.getValue())+"'>").reduce("",String::concat);
        byte[] html=("<!doctype html><meta name='referrer' content='no-referrer'><h1>Fixture provider consent</h1><form action='/consent'>"+hidden+
                "<button id='approve' name='decision' value='approve'>Allow Forge</button><button id='deny' name='decision' value='deny'>Decline</button></form>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Cross-Origin-Opener-Policy","same-origin");
        exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");exchange.sendResponseHeaders(200,html.length);exchange.getResponseBody().write(html);exchange.close();
    }
    private void consent(HttpExchange exchange) throws java.io.IOException {
        var p=parameters(exchange.getRequestURI().getRawQuery());
        if(!callback.equals(p.get("redirect_uri"))) {reply(exchange,400,Map.of("error","invalid_request"));return;}
        var result=new LinkedHashMap<String,String>();result.put("state",p.get("state"));result.put("iss",issuer());
        if("approve".equals(p.get("decision"))) {String code=UUID.randomUUID().toString();challenges.put(code,p.get("code_challenge"));sensitive.add(code);result.put("code",code);}
        else result.put("error","access_denied");
        exchange.getResponseHeaders().set("Location",callback+"?"+query(result));exchange.sendResponseHeaders(303,-1);exchange.close();
    }
    private void token(HttpExchange exchange) throws java.io.IOException {
        var p=parameters(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
        if(!CLIENT.equals(p.get("client_id")) || !SECRET.equals(p.get("client_secret")) || !resource.equals(p.get("resource"))) {
            reply(exchange,400,Map.of("error","invalid_client","error_description","provider-error-canary"));return;
        }
        boolean refresh="refresh_token".equals(p.get("grant_type"));
        if(!refresh) {
            sensitive.add(p.get("code_verifier"));
            String challenge=challenges.remove(p.get("code"));
            try {
                String actual=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(p.getOrDefault("code_verifier","").getBytes(StandardCharsets.US_ASCII)));
                if(challenge==null || !challenge.equals(actual) || !callback.equals(p.get("redirect_uri"))) {reply(exchange,400,Map.of("error","invalid_grant"));return;}
            } catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        } else if(!REFRESH.equals(p.get("refresh_token")) && !(REFRESH+"-rotated").equals(p.get("refresh_token"))) {reply(exchange,400,Map.of("error","invalid_grant"));return;}
        operations.add(refresh?"refresh":"exchange");
        reply(exchange,200,Map.of("access_token",refresh?ACCESS+"-rotated":ACCESS,"refresh_token",refresh?REFRESH+"-rotated":REFRESH,
                "token_type","Bearer","expires_in",600,"scope","tools"));
    }
    private void reply(HttpExchange exchange,int status,Object body) throws java.io.IOException {
        byte[] data=json.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,data.length);exchange.getResponseBody().write(data);exchange.close();
    }
    private static Map<String,String> parameters(String form) {
        Map<String,String> values=new LinkedHashMap<>();if(form==null)return values;
        for(String entry:form.split("&")){var pair=entry.split("=",2);values.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"");}return values;
    }
    private static String query(Map<String,String> values){return values.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));}
    private static String escape(String value){return value.replace("&","&amp;").replace("'","&#39;").replace("<","&lt;");}
    public void close(){server.stop(0);}
}
