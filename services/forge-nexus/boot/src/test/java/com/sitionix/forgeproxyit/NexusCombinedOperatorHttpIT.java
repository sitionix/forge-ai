package com.sitionix.forgeproxyit;
import static org.assertj.core.api.Assertions.*;
import com.sitionix.forgeai.Application;
import com.sitionix.forgeai.api.remoteaccess.RemoteAccessOperatorController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
@SpringBootTest(classes=Application.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    "spring.config.import=","spring.docker.compose.enabled=false","server.address=127.0.0.1",
    "forge.mcp.enabled=true","forge.remote-access.enabled=true","forge.remote-access.operator-origin=http://127.0.0.1:9099"})
@org.springframework.context.annotation.Import(NexusCombinedOperatorHttpIT.DispatchFixture.class)
@DirtiesContext
class NexusCombinedOperatorHttpIT {
    static final String BASE="/fgaisox/api/v1/infrastructure/agents/remote-access";
    static final String OPERATOR="o".repeat(43), SERVICE="s".repeat(43);
    static final String GENERAL=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    static Path directory; static HttpServer upstream;static final AtomicInteger calls=new AtomicInteger();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        directory=Files.createTempDirectory("stage6-operator-http-");
        for (String name:List.of("operator","service")) {
            var file=directory.resolve(name);Files.writeString(file,name.equals("operator")?OPERATOR:SERVICE);
            Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));registry.add("forge.remote-access."+name+"-secret-file",file::toString);
        }
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/api/v1/remote-access/capabilities",exchange -> {
            calls.incrementAndGet();
            if (!("Bearer "+SERVICE).equals(exchange.getRequestHeaders().getFirst("Authorization"))) { exchange.sendResponseHeaders(401,-1);exchange.close();return; }
            byte[] body="{\"ready\":true,\"supportedOperations\":[\"CONNECT\"],\"diagnostics\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });upstream.createContext("/api/v1/integrations/mcp/connections",exchange -> {
            calls.incrementAndGet();
            if (!("Bearer "+GENERAL).equals(exchange.getRequestHeaders().getFirst("Authorization"))) { exchange.sendResponseHeaders(401,-1);exchange.close();return; }
            byte[] body="[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });upstream.start();
        var general=directory.resolve("general");Files.writeString(general,GENERAL);Files.setPosixFilePermissions(general,PosixFilePermissions.fromString("rw-------"));
        registry.add("forge.mcp.agent-service-credential-file",general::toString);
        registry.add("forge.ai.infrastructure.agent.base-url",() -> "http://127.0.0.1:"+upstream.getAddress().getPort());
    }
    @AfterAll static void stop() throws Exception { upstream.stop(0);for(String name:List.of("operator","service","general"))Files.deleteIfExists(directory.resolve(name));Files.deleteIfExists(directory); }
    @Autowired ServletWebServerApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Test void actualHttpLoginCookieOriginAndServiceCredentialBoundary() throws Exception {
        calls.set(0);
        String denied=request("GET","/capabilities","","");assertThat(denied).startsWith("HTTP/1.1 401");assertThat(calls.get()).isZero();
        String login=request("POST","/operator/login","Origin: http://127.0.0.1:9099\r\n","{\"secret\":\""+OPERATOR+"\"}");
        assertThat(login).startsWith("HTTP/1.1 200").contains("HttpOnly","SameSite=Strict","Path=/fgaisox").doesNotContain(OPERATOR);
        String cookie=Arrays.stream(login.split("\r\n")).filter(line -> line.toLowerCase().startsWith("set-cookie:")).findFirst().orElseThrow().substring(12).split(";",2)[0];
        String authorized=request("GET","/capabilities","Cookie: "+cookie+"\r\n","");
        assertThat(authorized).startsWith("HTTP/1.1 200").contains("CONNECT").doesNotContain(SERVICE);assertThat(calls.get()).isEqualTo(1);
        String crossOrigin=request("GET","/capabilities","Cookie: "+cookie+"\r\nOrigin: http://evil.test\r\n","");
        assertThat(crossOrigin).startsWith("HTTP/1.1 403");assertThat(calls.get()).isEqualTo(1);
        String csrfMissing=request("POST","/operator/logout","Cookie: "+cookie+"\r\nOrigin: http://127.0.0.1:9099\r\n","");
        assertThat(csrfMissing).startsWith("HTTP/1.1 403");
    }
    @Test void servletScopedSecurityAlsoProtectsEncodedAndMatrixPaths() throws Exception {
        for (String prefix:List.of(BASE.replace("remote-access","%72emote-access"),BASE+";v=1")) {
            String response=requestAt("GET",prefix+"/capabilities","","");
            assertThat(response).doesNotStartWith("HTTP/1.1 200");
            assertThat(response).startsWith("HTTP/1.1 40");
        }
    }
    @Test void oneCookieCoversGeneralControlRotationLogoutAndUnknownAlias() throws Exception {
        String login=request("POST","/operator/login","Origin: http://127.0.0.1:9099\r\n","{\"secret\":\""+OPERATOR+"\"}");
        assertThat(login).startsWith("HTTP/1.1 200").contains("Path=/fgaisox;").doesNotContain("FG_SESSION");
        String cookie=cookie(login),csrf=csrf(login);
        var jar=new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL);
        String setCookie=Arrays.stream(login.split("\r\n")).filter(line -> line.toLowerCase().startsWith("set-cookie:")).findFirst().orElseThrow().substring(12);
        jar.put(URI.create("http://127.0.0.1:9099"+BASE+"/operator/login"),Map.of("Set-Cookie",List.of(setCookie)));
        assertThat(jar.get(URI.create("http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/agents/integrations/mcp/connections"),Map.of()).get("Cookie")).anyMatch(value -> value.contains(cookie));
        assertThat(requestAt("GET","/fgaisox/api/v1/infrastructure/agents/integrations/mcp/connections","Cookie: "+cookie+"\r\n","")).startsWith("HTTP/1.1 200");
        assertThat(requestAt("GET","/fgaisox/api/v1/operator/session","","")).startsWith("HTTP/1.1 401");
        assertThat(requestAt("POST","/fgaisox/api/v1/operator/session","Origin: http://127.0.0.1:9099\r\n","{}")).startsWith("HTTP/1.1 403");
        assertThat(requestAt("GET","/fgaisox/api/v1/operator/session","Cookie: "+cookie+"\r\n","")).startsWith("HTTP/1.1 404");
        String relogin=request("POST","/operator/login","Cookie: "+cookie+"\r\nOrigin: http://127.0.0.1:9099\r\n","{\"secret\":\""+OPERATOR+"\"}");
        String rotated=cookie(relogin);assertThat(rotated).isNotEqualTo(cookie);
        assertThat(requestAt("GET","/fgaisox/api/v1/infrastructure/agents/integrations/mcp/connections","Cookie: "+cookie+"\r\n","")).startsWith("HTTP/1.1 401");
        assertThat(request("POST","/operator/logout","Cookie: "+rotated+"\r\nOrigin: http://127.0.0.1:9099\r\nX-CSRF-TOKEN: "+csrf(relogin)+"\r\n","")).startsWith("HTTP/1.1 204");
        assertThat(requestAt("GET","/fgaisox/api/v1/infrastructure/agents/integrations/mcp/connections","Cookie: "+rotated+"\r\n","")).startsWith("HTTP/1.1 401");
    }
    static String cookie(String response) {
        return Arrays.stream(response.split("\r\n")).filter(line -> line.toLowerCase().startsWith("set-cookie:")).findFirst().orElseThrow().substring(12).split(";",2)[0];
    }
    static String csrf(String response) {
        var match=java.util.regex.Pattern.compile("\"csrfToken\":\"([^\"]+)\"").matcher(response);assertThat(match.find()).isTrue();return match.group(1);
    }
    @org.springframework.boot.test.context.TestConfiguration
    static class DispatchFixture {
        @org.springframework.context.annotation.Bean DispatchController dispatchController() { return new DispatchController(); }
    }
    @org.springframework.web.bind.annotation.RestController
    static class DispatchController {
        static final java.util.concurrent.atomic.AtomicInteger dispatches=new java.util.concurrent.atomic.AtomicInteger();
        @org.springframework.web.bind.annotation.GetMapping("/static/security-probe/{kind}.html")
        void dispatch(@org.springframework.web.bind.annotation.PathVariable String kind,jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) throws Exception {
            dispatches.incrementAndGet();
            String target="/api/v1/infrastructure/agents/integrations/mcp/connections";
            if(kind.equals("async")) { var async=request.startAsync();async.dispatch(target); }
            else if(kind.equals("include")) request.getRequestDispatcher(target).include(request,response);
            else request.getRequestDispatcher(target).forward(request,response);
        }
    }
    @Test void publicEntryCannotDispatchToUnauthenticatedManagement() throws Exception {
        int before=calls.get();DispatchController.dispatches.set(0);
        for(String kind:List.of("forward","include","async")) requestAt("GET","/fgaisox/static/security-probe/"+kind+".html","","");
        assertThat(calls.get()).isEqualTo(before);assertThat(DispatchController.dispatches.get()).isEqualTo(3);
    }
    // Raw local HTTP lets the random test listener receive the exact configured Host;
    // production uses the configured fixed origin. Agent here is explicitly an HTTP stub.
    String request(String method,String path,String extra,String body) throws Exception {
        return requestAt(method,BASE+path,extra,body);
    }
    String requestAt(String method,String path,String extra,String body) throws Exception {
        try (var socket=new Socket("127.0.0.1",context.getWebServer().getPort())) {
            socket.setSoTimeout(15000);
            var content=body.getBytes(StandardCharsets.UTF_8);
            String headers=method+" "+path+" HTTP/1.1\r\nHost: 127.0.0.1:9099\r\nConnection: close\r\nContent-Type: application/json\r\n"+extra+"Content-Length: "+content.length+"\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().write(content);socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        }
    }
}
