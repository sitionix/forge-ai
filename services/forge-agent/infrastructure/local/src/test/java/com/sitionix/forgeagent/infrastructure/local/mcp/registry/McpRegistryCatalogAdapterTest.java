package com.sitionix.forgeagent.infrastructure.local.mcp.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.exception.McpRegistryUnavailableException;
import com.sitionix.forgeagent.domain.port.McpRegistryCatalog;
import com.github.benmanes.caffeine.cache.Ticker;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import org.springframework.web.client.RestClient;

class McpRegistryCatalogAdapterTest {
    @Test
    void productionCaffeineCacheExpiresAtFiveMinutesAndKeysEveryRequestParameter() {
        long[] nanos = {0};
        Ticker ticker = () -> nanos[0];
        List<String> calls = new ArrayList<>();
        McpRegistryHttpClient client = (search, cursor, limit, version) -> {
            calls.add(search + "|" + cursor + "|" + limit);
            return new McpRegistryHttpClient.Page(List.of(), new McpRegistryHttpClient.Metadata("next"));
        };
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("forge.mcp.enabled", "true")));
            context.register(CacheTestConfiguration.class);
            context.registerBean(org.springframework.cache.CacheManager.class,
                    () -> new McpRegistryHttpClientConfiguration().cacheManager(ticker));
            context.registerBean(McpRegistryHttpClient.class, () -> client);
            context.registerBean(McpRegistryCatalogAdapter.class);
            context.refresh();
            var catalog = context.getBean(McpRegistryCatalog.class);

            catalog.list("one", "page", 20);
            catalog.list("one", "page", 20);
            assertThat(calls).containsExactly("one|page|20");

            catalog.list("two", "page", 20);
            catalog.list("one", "other", 20);
            catalog.list("one", "page", 21);
            assertThat(calls).hasSize(4);

            nanos[0] = Duration.ofMinutes(5).toNanos();
            catalog.list("one", "page", 20);
            assertThat(calls).containsExactly("one|page|20", "two|page|20",
                    "one|other|20", "one|page|21", "one|page|20");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class CacheTestConfiguration {}

    @Test
    void productionHttpClientSendsOnePageAndMapsRemoteHttpMetadata() throws Exception {
        String[] request = new String[2];
        HttpServer server = stub("""
                {"servers":[
                  {"server":{"name":"io.example/remote","title":"Remote","description":"Search records",
                    "version":"1.0.0","remotes":[{"type":"streamable-http","url":"https://example.org/mcp"}]}},
                  {"server":{"name":"io.example/tenant","remotes":[{"type":"streamable-http","url":"https://{tenant_id}.example.org/mcp"}]}},
                  {"server":{"name":"io.example/region","remotes":[{"type":"streamable-http","url":"https://example.org/{region}/mcp"}]}},
                  {"server":{"name":"io.example/base","remotes":[{"type":"streamable-http","url":"{baseUrl}/mcp"}]}},
                  {"server":{"name":"io.example/local","remotes":[{"type":"stdio","url":"local"}]}},
                  {"server":{"name":"io.example/sse","remotes":[{"type":"sse","url":"https://example.org/sse"}]}},
                  {"server":{"name":"io.example/javascript","remotes":[{"type":"streamable-http","url":"javascript:alert(1)"}]}},
                  {"server":{"name":"io.example/invalid","remotes":[{"type":"streamable-http","url":"https://user@example.org/mcp"}]}}
                ],"metadata":{"nextCursor":"cursor-2"}}
                """, 200, request);
        try {
            var page = adapter(server).list("find records", "cursor-1", 20);
            assertThat(request[0]).isEqualTo("/v0.1/servers?search=find%20records&cursor=cursor-1&limit=20&version=latest");
            assertThat(request[1]).isNull();
            assertThat(page.servers()).hasSize(4);
            assertThat(page.servers().getFirst().name()).isEqualTo("io.example/remote");
            assertThat(page.servers().getFirst().title()).isEqualTo("Remote");
            assertThat(page.servers().getFirst().description()).isEqualTo("Search records");
            assertThat(page.servers().getFirst().version()).isEqualTo("1.0.0");
            assertThat(page.servers().getFirst().endpoint()).isEqualTo("https://example.org/mcp");
            assertThat(page.servers()).extracting(serverEntry -> serverEntry.endpoint())
                    .containsExactly("https://example.org/mcp", "https://{tenant_id}.example.org/mcp",
                            "https://example.org/{region}/mcp", "{baseUrl}/mcp");
            assertThat(page.nextCursor()).isEqualTo("cursor-2");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void emptyFilteredPageKeepsRegistryCursor() throws Exception {
        HttpServer server = stub("""
                {"servers":[{"server":{"name":"io.example/local","remotes":[{"type":"stdio","url":"local"}]}}],
                 "metadata":{"nextCursor":"next-page"}}
                """, 200, new String[2]);
        try {
            var page = adapter(server).list(null, null, 20);
            assertThat(page.servers()).isEmpty();
            assertThat(page.nextCursor()).isEqualTo("next-page");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void registryFailureIsNotAnEmptySuccess() throws Exception {
        HttpServer server = stub("unavailable", 503, new String[2]);
        try {
            assertThatThrownBy(() -> adapter(server).list(null, null, 20))
                    .isInstanceOf(McpRegistryUnavailableException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedRegistryJsonIsUnavailable() throws Exception {
        HttpServer server = stub("{invalid-json", 200, new String[2]);
        try {
            assertThatThrownBy(() -> adapter(server).list(null, null, 20))
                    .isInstanceOf(McpRegistryUnavailableException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void registryRedirectIsNotFollowed() throws Exception {
        boolean[] targetCalled = {false};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v0.1/servers", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetCalled[0] = true;
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            assertThatThrownBy(() -> adapter(server).list(null, null, 20))
                    .isInstanceOf(McpRegistryUnavailableException.class);
            assertThat(targetCalled[0]).isFalse();
        } finally {
            server.stop(0);
        }
    }

    private static McpRegistryCatalogAdapter adapter(HttpServer server) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        var client = new McpRegistryHttpClientConfiguration().mcpRegistryHttpClient(
                RestClient.builder(), baseUrl, Duration.ofSeconds(2), Duration.ofSeconds(2));
        return new McpRegistryCatalogAdapter(client);
    }

    private static HttpServer stub(String body, int status, String[] request) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v0.1/servers", exchange -> {
            request[0] = exchange.getRequestURI().toString();
            request[1] = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        return server;
    }
}
