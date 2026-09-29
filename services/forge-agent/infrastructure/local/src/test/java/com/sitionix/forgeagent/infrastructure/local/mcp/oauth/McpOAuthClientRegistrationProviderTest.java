package com.sitionix.forgeagent.infrastructure.local.mcp.oauth;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.exception.McpOAuthException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.infrastructure.local.mcp.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
class McpOAuthClientRegistrationProviderTest {
    @TempDir Path temporary;
    org.springframework.boot.ssl.SslBundles bundles;
    HttpServer server;URI base;
    String response="{\"client_id\":\"dynamic-client\",\"token_endpoint_auth_method\":\"none\"}";
    int status=201;
    final List<String> paths=new CopyOnWriteArrayList<>(),bodies=new CopyOnWriteArrayList<>(),headers=new CopyOnWriteArrayList<>();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{paths.add(exchange.getRequestURI().getPath());bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            if(exchange.getRequestHeaders().getFirst("Authorization")!=null)headers.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
    }
    @AfterEach void cleanup(){server.stop(0);}
    @Test void dcrIsTypedUsesControlledRedirectAndSendsNoForgeCredentials() throws Exception {
        try(var context=context(Map.of())) {
            var registered=resolve(context,metadata());assertThat(registered.configuration().clientId()).isEqualTo("dynamic-client");
            assertThat(registered.credentials().clientSecret()).isNull();assertThat(paths).containsExactly("/register");
            var body=new ObjectMapper().readTree(bodies.get(0));assertThat(body.get("client_name").asText()).isEqualTo("Forge");
            assertThat(body.get("application_type").asText()).isEqualTo("native");assertThat(body.get("token_endpoint_auth_method").asText()).isEqualTo("none");
            assertThat(body.get("redirect_uris").get(0).asText()).isEqualTo("http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/agents/integrations/mcp/oauth/callback");
            assertThat(headers).isEmpty();assertThat(registered.toString()).doesNotContain("secret");
        }
    }
    @Test void installationClientHasPriorityAndSecretIsLoadedOnlyFromProtectedFile() throws Exception {
        Path secret=temporary.resolve("client-secret");Files.writeString(secret,"synthetic-client-canary");Files.setPosixFilePermissions(secret,PosixFilePermissions.fromString("rw-------"));
        try(var context=context(Map.of("forge.mcp.oauth.clients[0].issuer",base.toString(),"forge.mcp.oauth.clients[0].client-id","installed-client",
                "forge.mcp.oauth.clients[0].client-authentication-method","client_secret_post","forge.mcp.oauth.clients[0].client-secret-file",secret.toString()))) {
            var registered=resolve(context,metadata());assertThat(registered.configuration().clientId()).isEqualTo("installed-client");
            assertThat(registered.credentials().clientSecret()).isEqualTo("synthetic-client-canary");assertThat(registered.toString()).doesNotContain("canary");assertThat(paths).isEmpty();
        }
    }
    @Test void clientConfiguredForAnotherIssuerIsNeverReused(){
        try(var context=context(Map.of("forge.mcp.oauth.clients[0].issuer","https://other.example","forge.mcp.oauth.clients[0].client-id","other-client","forge.mcp.oauth.clients[0].client-authentication-method","none"))) {
            assertThat(resolve(context,metadata()).configuration().clientId()).isEqualTo("dynamic-client");assertThat(bodies.get(0)).doesNotContain("other-client");
        }
    }
    @Test void unsupportedRegistrationIsSetupRequiredAndWritesNothing(){
        var m=metadata();var unsupported=new McpAuthenticationMetadata(true,m.resource(),m.issuer(),m.authorizationEndpoint(),m.tokenEndpoint(),null,null,false,m.scopes(),m.clientAuthenticationMethods(),m.codeChallengeMethods());
        try(var context=context(Map.of())){assertThatThrownBy(()->resolve(context,unsupported)).isInstanceOfSatisfying(McpOAuthException.class,e->assertThat(e.code()).isEqualTo("MCP_OAUTH_SETUP_REQUIRED"));assertThat(paths).isEmpty();}
    }
    @Test void dcrCannotChangeMethodRedirectOrReturnInvalidSecret(){
        try(var context=context(Map.of())) {
            for(String body:List.of("{\"client_id\":123}","{\"client_id\":\"x\"}","{\"client_id\":\"x\",\"token_endpoint_auth_method\":\"none\",\"client_secret_expires_at\":1}","{\"client_id\":\"x\",\"token_endpoint_auth_method\":\"private_key_jwt\"}",
                    "{\"client_id\":\"x\",\"redirect_uris\":[\"https://evil.example/callback\"]}","{\"client_id\":\"x\",\"client_secret\":{\"secret-canary\":true}}")) {
                response=body;assertThatThrownBy(()->resolve(context,metadata())).isInstanceOf(McpOAuthException.class).hasNoCause().hasMessageNotContaining("canary");
            }
        }
    }
    @Test void expiredDeadlineAndPrivateRegistrationMakeZeroCalls(){
        try(var context=context(Map.of())) {
            var provider=context.getBean(McpOAuthClientRegistrationProvider.class);
            assertThatThrownBy(()->provider.resolve(metadata(),System.nanoTime()-1)).isInstanceOf(McpOAuthException.class);
            var m=metadata();var denied=new McpAuthenticationMetadata(true,m.resource(),m.issuer(),m.authorizationEndpoint(),m.tokenEndpoint(),null,URI.create("http://127.0.0.1:1/register"),false,m.scopes(),m.clientAuthenticationMethods(),m.codeChallengeMethods());
            assertThatThrownBy(()->resolve(context,denied)).isInstanceOf(McpOAuthException.class);assertThat(paths).isEmpty();
        }
    }
    @Test void cimdUsesProductionTlsAndDoesNotRegisterAnotherClient() throws Exception {
        Path store=temporary.resolve("cimd.p12");String password="synthetic-password";
        var keytool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),"-genkeypair","-alias","fixture","-keyalg","RSA","-keystore",store.toString(),"-storetype","PKCS12","-storepass",password,"-keypass",password,"-dname","CN=127.0.0.1","-ext","SAN=ip:127.0.0.1","-validity","1","-noprompt").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try{assertThat(keytool.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(keytool.exitValue()).isZero();}finally{if(keytool.isAlive())keytool.destroyForcibly();}
        var keys=java.security.KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(store)){keys.load(input,password.toCharArray());}
        var trust=java.security.KeyStore.getInstance("PKCS12");trust.load(null,null);trust.setCertificateEntry("fixture",keys.getCertificate("fixture"));
        var ssl=org.springframework.boot.ssl.SslBundle.of(org.springframework.boot.ssl.SslStoreBundle.of(keys,password,trust));
        bundles=new org.springframework.boot.ssl.DefaultSslBundleRegistry("fixture",org.springframework.boot.ssl.SslBundle.of(org.springframework.boot.ssl.SslStoreBundle.of(null,null,trust)));
        var https=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);https.setHttpsConfigurator(new HttpsConfigurator(ssl.createSslContext()));
        String clientId="https://127.0.0.1:"+https.getAddress().getPort()+"/client.json";
        String callback="http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/agents/integrations/mcp/oauth/callback";
        response="{\"client_id\":\""+clientId+"\",\"client_name\":\"Forge\",\"redirect_uris\":[\""+callback+"\"],\"token_endpoint_auth_method\":\"none\"}";
        https.createContext("/client.json",exchange->{paths.add("/client.json");byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});https.start();
        var m=metadata();var cimd=new McpAuthenticationMetadata(true,m.resource(),m.issuer(),m.authorizationEndpoint(),m.tokenEndpoint(),null,m.registrationEndpoint(),true,m.scopes(),m.clientAuthenticationMethods(),m.codeChallengeMethods());
        try(var context=context(Map.of("forge.mcp.oauth.client-id-metadata-uri",clientId,"forge.mcp.oauth.ssl-bundle","fixture","forge.mcp.probe.allowed-private-endpoints","127.0.0.1:"+server.getAddress().getPort()+",127.0.0.1:"+https.getAddress().getPort()))) {
            assertThat(resolve(context,cimd).configuration().clientId()).isEqualTo(clientId);assertThat(paths).containsExactly("/client.json");
            response=response.replace(clientId,"https://wrong.example/client.json");assertThatThrownBy(()->resolve(context,cimd)).isInstanceOf(McpOAuthException.class);
            status=404;assertThatThrownBy(()->resolve(context,cimd)).isInstanceOf(McpOAuthException.class);assertThat(paths).doesNotContain("/register");
        }finally{https.stop(0);}
    }
    @Test void duplicateIssuerConfigurationFailsClosed(){assertThatThrownBy(()->context(Map.of("forge.mcp.oauth.clients[0].issuer",base.toString(),"forge.mcp.oauth.clients[0].client-id","first","forge.mcp.oauth.clients[1].issuer",base.toString(),"forge.mcp.oauth.clients[1].client-id","second"))).isInstanceOf(RuntimeException.class);assertThat(paths).isEmpty();}
    @Test void registrationFailureIsNotEmptySuccess(){status=503;response="secret-canary";try(var context=context(Map.of())) {
        assertThatThrownBy(()->resolve(context,metadata())).isInstanceOf(McpOAuthException.class).hasNoCause().hasMessageNotContaining("canary");}}
    private McpOAuthClientRegistration resolve(AnnotationConfigApplicationContext context,McpAuthenticationMetadata metadata){return context.getBean(McpOAuthClientRegistrationProvider.class).resolve(metadata,System.nanoTime()+java.time.Duration.ofSeconds(3).toNanos());}
    private McpAuthenticationMetadata metadata(){return new McpAuthenticationMetadata(true,URI.create("https://mcp.example/mcp"),base,base.resolve("/authorize"),base.resolve("/token"),null,base.resolve("/register"),false,Set.of("read"),Set.of("none","client_secret_post"),Set.of("S256"));}
    private AnnotationConfigApplicationContext context(Map<String,Object> values){
        var properties=new HashMap<>(values);properties.putIfAbsent("forge.mcp.probe.allowed-private-endpoints","127.0.0.1:"+server.getAddress().getPort());
        var context=new AnnotationConfigApplicationContext();context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",properties));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper());context.registerBean(McpCredentialCipher.class,()->new AesGcmMcpCredentialCipher(()->new McpLocalKeys("fixture",Map.of("fixture",new byte[32]))));
        if(bundles!=null)context.registerBean(org.springframework.boot.ssl.SslBundles.class,()->bundles);
        context.register(McpOAuthHttpConfiguration.class,McpOAuthDiscoveryConfiguration.class);try{context.refresh();return context;}catch(RuntimeException error){context.close();throw error;}
    }
}
