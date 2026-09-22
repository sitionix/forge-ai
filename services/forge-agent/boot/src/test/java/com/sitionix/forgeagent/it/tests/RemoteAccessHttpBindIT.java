package com.sitionix.forgeagent.it.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeagent.ForgeAgentApplication;
import com.sitionix.forgeagent.infrastructure.local.remoteaccess.RemoteAccessChannelServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(classes = ForgeAgentApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"forge.agent.remote-access.channel-enabled=true", "FORGE_AGENT_HOST=127.0.0.1",
                "server.port=0", "forge.agent.worker.scheduling-enabled=false"})
@DirtiesContext
class RemoteAccessHttpBindIT {
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        DATABASE.start();
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    @AfterAll static void stop() { DATABASE.stop(); }

    // Only the unrelated privileged Unix socket lifecycle is replaced; HTTP startup/bind is real.
    @MockBean private RemoteAccessChannelServer channelServer;
    @Autowired private ServletWebServerApplicationContext context;

    @Test void enabledAgentActuallyBindsAndServesOnlyItsConfiguredLoopbackAddress() throws Exception {
        var server = (TomcatWebServer) context.getWebServer();
        var protocol = (AbstractProtocol<?>) server.getTomcat().getConnector().getProtocolHandler();
        assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(protocol.getAddress().isLoopbackAddress()).isTrue();
        assertThat(protocol.getAddress().getHostAddress()).isEqualTo("127.0.0.1");
        assertThat(protocol.getLocalPort()).isPositive().isEqualTo(server.getPort());
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/actuator/info"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }
        assertThat(context.getEnvironment().getProperty("forge.agent.remote-access.channel-enabled", Boolean.class)).isTrue();
    }
}
