package com.sitionix.forgeai.infrastructure.localcli.adapter;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalCliOperatorServiceRuntimeAdapterTest {

    private final LocalCliOperatorServiceRuntimeAdapter adapter = new LocalCliOperatorServiceRuntimeAdapter();

    @Test
    void givenReachableHealthcheckUrl_whenHealthcheck_thenReturnUp() throws Exception {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/health", exchange -> {
            final byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            final int port = server.getAddress().getPort();

            final var actual = this.adapter.healthcheck("http://127.0.0.1:" + port + "/actuator/health");

            assertThat(actual.status()).isEqualTo("UP");
            assertThat(actual.containerName()).isEqualTo("http://127.0.0.1:" + port + "/actuator/health");
            assertThat(actual.message()).isEqualTo("HTTP 200");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void givenMissingHealthcheckUrl_whenHealthcheck_thenReturnDown() {
        final var actual = this.adapter.healthcheck(null);

        assertThat(actual.status()).isEqualTo("DOWN");
        assertThat(actual.message()).isEqualTo("Healthcheck URL is not configured.");
    }
}
