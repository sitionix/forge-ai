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
    properties={"forge.agent.remote-access.management-enabled=true","server.address=127.0.0.1","forge.agent.worker.scheduling-enabled=false"})
@DirtiesContext
class RemoteAccessManagementHttpIT {
    static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    static final String SECRET="s".repeat(43);
    static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        DB.start();directory=Files.createTempDirectory("stage6-http-");var file=directory.resolve("service");
        Files.writeString(file,SECRET);Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        registry.add("spring.datasource.url",DB::getJdbcUrl);registry.add("spring.datasource.username",DB::getUsername);registry.add("spring.datasource.password",DB::getPassword);
        registry.add("forge.agent.remote-access.service-secret-file",file::toString);
    }
    @AfterAll static void cleanup() throws Exception { DB.stop();Files.deleteIfExists(directory.resolve("service"));Files.deleteIfExists(directory); }
    @Autowired ServletWebServerApplicationContext context;
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
