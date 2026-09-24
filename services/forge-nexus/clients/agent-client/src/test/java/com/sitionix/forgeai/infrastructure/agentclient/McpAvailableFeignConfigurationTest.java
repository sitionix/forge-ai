package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import feign.Client;
import feign.Request;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpAvailableFeignConfigurationTest {
    @Test
    void serviceBearerRequestDoesNotFollowAgentRedirect() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            var properties = new ForgeAgentClientProperties();
            properties.setConnectTimeout(Duration.ofSeconds(2));
            properties.setReadTimeout(Duration.ofSeconds(5));
            Request.Options options = new McpAvailableFeignConfiguration().mcpAvailableOptions(properties);
            Request request = Request.create(Request.HttpMethod.GET,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/start",
                    Map.of("Authorization", List.of("Bearer synthetic-service")), null,
                    StandardCharsets.UTF_8);

            try (var response = new Client.Default(null, null).execute(request, options)) {
                assertThat(options.isFollowRedirects()).isFalse();
                assertThat(response.status()).isEqualTo(302);
            }
        } finally {
            server.stop(0);
        }
    }
}
