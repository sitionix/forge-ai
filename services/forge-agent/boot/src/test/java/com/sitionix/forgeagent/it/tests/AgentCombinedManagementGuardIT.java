package com.sitionix.forgeagent.it.tests;
import static org.assertj.core.api.Assertions.*;
import com.sitionix.forgeagent.ForgeAgentApplication;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
@SpringBootTest(classes=ForgeAgentApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"forge.mcp.enabled=true","forge.agent.remote-access.management-enabled=true","server.address=127.0.0.1","forge.agent.worker.scheduling-enabled=false"})
@org.springframework.context.annotation.Import(AgentCombinedManagementGuardIT.DispatchFixture.class)
@DirtiesContext
class AgentCombinedManagementGuardIT {
    static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    static final String SECRET="s".repeat(43);
    static final String GENERAL=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        DB.start();directory=Files.createTempDirectory("stage6-http-");var file=directory.resolve("service");
        Files.writeString(file,SECRET);Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        registry.add("spring.datasource.url",DB::getJdbcUrl);registry.add("spring.datasource.username",DB::getUsername);registry.add("spring.datasource.password",DB::getPassword);
        registry.add("forge.agent.remote-access.service-secret-file",file::toString);
        for (String name:java.util.List.of("general","key","db")) {
            Path protectedFile=directory.resolve(name);
            Files.writeString(protectedFile,name.equals("general")?GENERAL:name.equals("db")?DB.getPassword():"active=k1\nkey.k1="+java.util.Base64.getEncoder().encodeToString(new byte[32])+"\n");
            Files.setPosixFilePermissions(protectedFile,PosixFilePermissions.fromString("rw-------"));
            registry.add("forge.mcp."+(name.equals("general")?"service-credential-file":name.equals("db")?"database-credential-file":"key-file"),protectedFile::toString);
        }
    }
    @AfterAll static void cleanup() throws Exception { DB.stop();for(String name:java.util.List.of("service","general","key","db"))Files.deleteIfExists(directory.resolve(name));Files.deleteIfExists(directory); }
    @Autowired ServletWebServerApplicationContext context;
    @org.springframework.boot.test.mock.mockito.MockBean com.sitionix.forgeagent.infrastructure.local.runtime.RuntimeBoundaryVerifier verifier;
    @org.springframework.boot.test.mock.mockito.SpyBean com.sitionix.forgeagent.api.ForgeAgentController controller;
    @org.springframework.boot.test.mock.mockito.SpyBean com.sitionix.forgeagent.api.remoteaccess.RemoteAccessController remoteController;
    @org.springframework.boot.test.context.TestConfiguration
    static class DispatchFixture {
        @org.springframework.context.annotation.Bean DispatchController dispatchController() { return new DispatchController(); }
    }
    @org.springframework.web.bind.annotation.RestController
    static class DispatchController {
        static final java.util.concurrent.atomic.AtomicInteger dispatches=new java.util.concurrent.atomic.AtomicInteger();
        @org.springframework.web.bind.annotation.GetMapping({"/api/v1/dispatch/{kind}","/api/v1/remote-access/dispatch/{kind}"})
        void dispatch(@org.springframework.web.bind.annotation.PathVariable String kind,jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) throws Exception {
            dispatches.incrementAndGet();
            String target=request.getRequestURI().contains("remote-access")?"/api/v1/projects":"/api/v1/remote-access/sessions";
            if(kind.equals("async")) { var async=request.startAsync();async.dispatch(target); }
            else if(kind.equals("include")) request.getRequestDispatcher(target).include(request,response);
            else request.getRequestDispatcher(target).forward(request,response);
        }
    }
    @Test void actualCrossAudienceForwardIncludeAndAsyncCannotInvokeTarget() throws Exception {
        DispatchController.dispatches.set(0);
        for(String kind:java.util.List.of("forward","include","async")) {
            org.mockito.Mockito.clearInvocations(controller,remoteController);
            send("GET","/api/v1/dispatch/"+kind,GENERAL,"");
            send("GET","/api/v1/remote-access/dispatch/"+kind,SECRET,"");
            org.mockito.Mockito.verifyNoInteractions(controller,remoteController);
        }
        assertThat(DispatchController.dispatches.get()).isEqualTo(6);
    }
    @Test void audienceOwnershipAndMcpCrudOnActualTomcat() throws Exception {
        org.mockito.Mockito.clearInvocations(controller);
        for (String path:java.util.List.of("/api/v1/projects","/api/v1/integrations/mcp/connections")) {
            for(String token:java.util.List.of("",SECRET,"wrong")) assertThat(send("GET",path,token,"").statusCode()).isEqualTo(401);
        }
        org.mockito.Mockito.verifyNoInteractions(controller);
        for(String path:java.util.List.of("/api/v1/remote-access/sessions","/api/v1/%72emote-access/sessions","/api/v1/remote-access;v=1/sessions")) {
            assertThat(send("GET",path,GENERAL,"").statusCode()).isEqualTo(401);
            assertThat(send("GET",path,SECRET,"").statusCode()).isEqualTo(200);
        }
        assertThat(send("GET","/api/v1/projects",GENERAL,"").statusCode()).isEqualTo(200);
        assertThat(send("GET","/api/v1/remote-access/unknown",SECRET,"").statusCode()).isEqualTo(404);
        var absent=java.util.UUID.randomUUID();org.mockito.Mockito.clearInvocations(remoteController);
        assertThat(send("DELETE","/api/v1/remote-access/invitations/"+absent,GENERAL,"").statusCode()).isEqualTo(401);
        org.mockito.Mockito.verifyNoInteractions(remoteController);
        assertThat(send("DELETE","/api/v1/remote-access/invitations/"+absent,SECRET,"").statusCode()).isEqualTo(404);
        org.mockito.Mockito.verify(remoteController).cancel(absent);
        String base="/api/v1/integrations/mcp/connections";
        String body=new String(getClass().getResourceAsStream("/forge-it/mockmvc/request/mcp-create-request.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        var created=send("POST",base,GENERAL,body);assertThat(created.statusCode()).isEqualTo(201);
        String id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.body()).path("id").asText();
        assertThat(send("GET",base+"/"+id,GENERAL,"").statusCode()).isEqualTo(200);
        String update=new String(getClass().getResourceAsStream("/forge-it/mockmvc/request/mcp-update-request.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(send("PUT",base+"/"+id,GENERAL,update).statusCode()).isEqualTo(200);
        assertThat(send("DELETE",base+"/"+id,GENERAL,"").statusCode()).isEqualTo(204);
        assertThat(send("GET",base+"/"+id,GENERAL,"").statusCode()).isEqualTo(404);
        org.mockito.Mockito.verify(verifier).verifyProtectedPaths(org.mockito.ArgumentMatchers.argThat(paths -> paths.contains(directory.resolve("service")) && paths.size()==4));
    }
    private HttpResponse<String> send(String method,String path,String token,String body) throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+path)).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body));
            if(!token.isEmpty())builder.header("Authorization","Bearer "+token);
            return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    @Test void encodedAndMatrixPathsCannotBypassServiceAuthentication() throws Exception {
        try (var client=HttpClient.newHttpClient()) {
            for (String prefix: new String[]{"/api/v1/remote-access;v=1", "/api/v1/%72emote-access"}) {
                var uri=URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+prefix+"/sessions");
                var read=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertThat(read.statusCode()).as("unauthenticated GET %s",prefix).isEqualTo(401);
                var mutation=client.send(HttpRequest.newBuilder(uri).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"pairingToken\":\"synthetic-token\"}")).build(),HttpResponse.BodyHandlers.ofString());
                assertThat(mutation.statusCode()).as("unauthenticated POST %s",prefix).isEqualTo(401);
            }
        }
    }

    @Test void realLoopbackHttpRequiresDedicatedServiceIdentity() throws Exception {
        try (var client=HttpClient.newHttpClient()) {
            String url="http://127.0.0.1:"+context.getWebServer().getPort()+"/api/v1/remote-access/sessions";
            var denied=client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(401);
            assertThat(denied.body()).doesNotContain(SECRET);
            var allowed=client.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization","Bearer "+SECRET).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(allowed.statusCode()).isEqualTo(200);assertThat(allowed.body()).isEqualTo("[]");
            assertThat(allowed.headers().firstValue("Cache-Control")).contains("no-store");
            var malformed=client.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization","Bearer "+SECRET)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"pairingToken\":{\"synthetic-secret\":1}}"))
                .build(),HttpResponse.BodyHandlers.ofString());
            assertThat(malformed.statusCode()).isEqualTo(400);assertThat(malformed.body()).doesNotContain("synthetic-secret");
        }
    }
}
