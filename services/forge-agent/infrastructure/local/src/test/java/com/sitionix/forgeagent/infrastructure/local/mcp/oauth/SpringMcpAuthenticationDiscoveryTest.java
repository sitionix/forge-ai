package com.sitionix.forgeagent.infrastructure.local.mcp.oauth;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.exception.McpOAuthException;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.infrastructure.local.mcp.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class SpringMcpAuthenticationDiscoveryTest {
    HttpServer server;
    AnnotationConfigApplicationContext context;
    URI base, endpoint;
    McpAuthenticationDiscovery discovery;
    final Map<String,Reply> replies=new ConcurrentHashMap<>();
    final List<String> paths=new CopyOnWriteArrayList<>();
    final List<String> credentials=new CopyOnWriteArrayList<>();
    final CountDownLatch release=new CountDownLatch(1);
    ExecutorService executor;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/", exchange->{
            paths.add(exchange.getRequestURI().getPath());
            for(String header:List.of("Authorization","Cookie"))if(exchange.getRequestHeaders().getFirst(header)!=null)credentials.add(header);
            var reply=replies.getOrDefault(exchange.getRequestURI().getPath(),new Reply(404,"{}",Map.of()));
            reply.headers().forEach((key,value)->exchange.getResponseHeaders().set(key,value));
            byte[] bytes=reply.body().getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(reply.status(),reply.headers().containsKey("X-Open")?0:bytes.length);
                exchange.getResponseBody().write(bytes);exchange.getResponseBody().flush();
                if(reply.headers().containsKey("X-Open"))release.await(5,TimeUnit.SECONDS);
            } catch(InterruptedException failure){Thread.currentThread().interrupt();}finally{exchange.close();}
        });server.start();base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());endpoint=base.resolve("/mcp");
        context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",Map.of(
            "forge.mcp.probe.allowed-private-endpoints","127.0.0.1:"+server.getAddress().getPort(),
            "forge.mcp.oauth.read-timeout","500ms","forge.mcp.probe.max-response-bytes",2048)));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper());
        context.registerBean(McpCredentialCipher.class,()->new AesGcmMcpCredentialCipher(()->new McpLocalKeys("fixture",Map.of("fixture",new byte[32]))));
        context.register(McpOAuthHttpConfiguration.class,McpOAuthDiscoveryConfiguration.class);context.refresh();
        discovery=context.getBean(McpAuthenticationDiscovery.class);
    }
    @AfterEach void cleanup(){release.countDown();if(context!=null)context.close();if(server!=null)server.stop(0);if(executor!=null)executor.shutdownNow();}
    @Test void challengeMetadataAndAuthoritativeScopeAreDiscoveredWithoutCredentials(){
        protectedMetadata();challenge("Basic realm=\"quoted,realm\", Bearer resource_metadata=\""+base+"/resource\", scope=\"read\"");
        var metadata=discover();
        assertThat(metadata.oauthRequired()).isTrue();assertThat(metadata.issuer()).isEqualTo(base);
        assertThat(metadata.scopes()).containsExactly("read");assertThat(metadata.codeChallengeMethods()).contains("S256");
        assertThat(paths).containsExactly("/mcp","/resource","/.well-known/oauth-authorization-server");assertThat(credentials).isEmpty();
    }
    @Test void standardPathAndRootFallbackUseOnlyNotFoundResponses(){
        protectedMetadata();challenge("Bearer");
        replies.put("/.well-known/oauth-protected-resource",replies.get("/resource"));
        assertThat(discover().oauthRequired()).isTrue();
        assertThat(paths).containsExactly("/mcp","/.well-known/oauth-protected-resource/mcp","/.well-known/oauth-protected-resource","/.well-known/oauth-authorization-server");
    }
    @Test void oidcPathInsertionThenAppendingAreSupported(){
        challenge("Bearer resource_metadata=\""+base+"/resource\"");
        reply("/resource",200,"{\"resource\":\""+endpoint+"\",\"authorization_servers\":[\""+base+"/tenant\"]}");
        reply("/tenant/.well-known/openid-configuration",200,asMetadata(base+"/tenant"));
        assertThat(discover().issuer()).isEqualTo(base.resolve("/tenant"));
        assertThat(paths).containsExactly("/mcp","/resource","/.well-known/oauth-authorization-server/tenant","/.well-known/openid-configuration/tenant","/tenant/.well-known/openid-configuration");
    }
    @Test void resourceOrIssuerMismatchFailsClosed(){
        protectedMetadata();challenge("Bearer resource_metadata=\""+base+"/resource\"");
        reply("/resource",200,"{\"resource\":\"https://other.example/mcp\",\"authorization_servers\":[\""+base+"\"]}");invalid();
        assertThat(paths).doesNotContain("/.well-known/oauth-authorization-server");
        protectedMetadata();reply("/.well-known/oauth-authorization-server",200,asMetadata("https://other.example"));invalid();
    }
    @Test void ambiguousServersAndUnsupportedPkceDoNotSelectArbitrarily(){
        protectedMetadata();challenge("Bearer resource_metadata=\""+base+"/resource\"");
        reply("/resource",200,"{\"resource\":\""+endpoint+"\",\"authorization_servers\":[\""+base+"\",\"https://other.example\"]}");invalid();
        protectedMetadata();reply("/.well-known/oauth-authorization-server",200,asMetadata(base.toString()).replace("S256","plain"));invalid();
    }
    @Test void challengeWithoutMetadataAndForbiddenAreSafeFailures(){challenge("Bearer");invalid();reply("/mcp",403,"provider-canary");
        assertThatThrownBy(this::discover).isInstanceOf(McpOAuthException.class).hasNoCause().hasMessageNotContaining("canary");}
    @Test void noAuthOpenResponseDoesNotWaitForTheMcpEventStream(){
        replies.put("/mcp",new Reply(200,"data: ready\n\n",Map.of("Content-Type","text/event-stream","X-Open","true")));
        long started=System.nanoTime();assertThat(discover().oauthRequired()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(2));assertThat(paths).containsExactly("/mcp");
    }
    @Test void malformedOversizeAndRedirectMetadataNeverFallThrough(){
        protectedMetadata();challenge("Bearer resource_metadata=\""+base+"/resource\"");
        for(String body:List.of("[]","not-json-canary","{}{}","{\"resource\":null}","x".repeat(2049))){reply("/resource",200,body);invalid();}
        replies.put("/resource",new Reply(302,"canary",Map.of("Location",base+"/destination")));invalid();
        assertThat(paths).doesNotContain("/destination","/.well-known/oauth-protected-resource");
    }
    @Test void maliciousPrivateMetadataIsDeniedBeforeSendingAnything(){
        challenge("Bearer resource_metadata=\"http://127.0.0.1:1/secret\"");
        assertThatThrownBy(this::discover).isInstanceOf(McpOAuthException.class).hasNoCause();assertThat(paths).containsExactly("/mcp");
    }
    @Test void duplicateChallengeParametersAndMalformedQuotesAreRejected(){
        for(String header:List.of("Bearer resource_metadata=\""+base+"/resource\", resource_metadata=\""+base+"/other\"","Bearer scope=\"unterminated","Bearer scope=\"read\", scope=\"write\"")){challenge(header);invalid();}
    }
    @Test void metadataWithoutAuthenticateHeaderUsesTheStandardWellKnownPath(){
        protectedMetadata();reply("/mcp",401,"{}");
        replies.put("/.well-known/oauth-protected-resource/mcp",replies.get("/resource"));
        assertThat(discover().oauthRequired()).isTrue();
    }
    @Test void stalledMetadataBodyIsBoundedAndNeverLeaksItsPayload(){
        challenge("Bearer resource_metadata=\""+base+"/resource\"");
        replies.put("/resource",new Reply(200,"{\"secret-canary\":",Map.of("Content-Type","application/json","X-Open","true")));
        long started=System.nanoTime();
        assertThatThrownBy(this::discover).isInstanceOf(McpOAuthException.class).hasNoCause().hasMessageNotContaining("canary");
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(2));
    }
    @Test void malformedNullMetadataCollectionsAreSafeInvalidResponses(){
        protectedMetadata();challenge("Bearer resource_metadata=\""+base+"/resource\"");
        for(String field:List.of("\"token_endpoint_auth_methods_supported\":[null]","\"client_id_metadata_document_supported\":\"true\"")){
            String body=asMetadata(base.toString());
            if(field.startsWith("\"token_endpoint"))body=body.replace("\"token_endpoint_auth_methods_supported\":[\"none\"]",field);
            else body=body.substring(0,body.length()-1)+","+field+"}";
            reply("/.well-known/oauth-authorization-server",200,body);invalid();
        }
    }
    @Test void expiredSharedDeadlineMakesZeroCalls(){
        assertThatThrownBy(()->discovery.discover(endpoint,System.nanoTime()-1)).isInstanceOf(McpOAuthException.class);assertThat(paths).isEmpty();
    }
    void invalid(){assertThatThrownBy(this::discover).isInstanceOf(McpOAuthException.class).hasNoCause().hasMessageNotContaining("canary");}
    com.sitionix.forgeagent.domain.model.McpAuthenticationMetadata discover(){return discovery.discover(endpoint,System.nanoTime()+Duration.ofSeconds(3).toNanos());}
    void challenge(String header){replies.put("/mcp",new Reply(401,"{}",Map.of("WWW-Authenticate",header)));}
    void reply(String path,int status,String body){replies.put(path,new Reply(status,body,Map.of("Content-Type","application/json")));}
    void protectedMetadata(){reply("/resource",200,"{\"resource\":\""+endpoint+"\",\"authorization_servers\":[\""+base+"\"],\"scopes_supported\":[\"read\",\"write\"]}");reply("/.well-known/oauth-authorization-server",200,asMetadata(base.toString()));}
    String asMetadata(String issuer){return "{\"issuer\":\""+issuer+"\",\"authorization_endpoint\":\""+base+"/authorize\",\"token_endpoint\":\""+base+"/token\",\"code_challenge_methods_supported\":[\"S256\"],\"grant_types_supported\":[\"authorization_code\"],\"response_types_supported\":[\"code\"],\"token_endpoint_auth_methods_supported\":[\"none\"]}";}
    record Reply(int status,String body,Map<String,String> headers){}
}
