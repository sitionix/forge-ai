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
    "forge.remote-access.enabled=true","forge.remote-access.operator-origin=http://127.0.0.1:9099"})
@DirtiesContext
class RemoteAccessOperatorHttpIT {
    static final String BASE="/fgaisox/api/v1/infrastructure/agents/remote-access";
    static final String OPERATOR="o".repeat(43), SERVICE="s".repeat(43);
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
        });upstream.start();
        registry.add("forge.ai.infrastructure.agent.base-url",() -> "http://127.0.0.1:"+upstream.getAddress().getPort());
    }
    @AfterAll static void stop() throws Exception { upstream.stop(0);for(String name:List.of("operator","service"))Files.deleteIfExists(directory.resolve(name));Files.deleteIfExists(directory); }
    @Autowired ServletWebServerApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Test void actualHttpLoginCookieOriginAndServiceCredentialBoundary() throws Exception {
        String denied=request("GET","/capabilities","","");assertThat(denied).startsWith("HTTP/1.1 401");assertThat(calls.get()).isZero();
        String login=request("POST","/operator/login","Origin: http://127.0.0.1:9099\r\n","{\"secret\":\""+OPERATOR+"\"}");
        assertThat(login).startsWith("HTTP/1.1 200").contains("HttpOnly","SameSite=Strict","Path="+BASE).doesNotContain(OPERATOR);
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
