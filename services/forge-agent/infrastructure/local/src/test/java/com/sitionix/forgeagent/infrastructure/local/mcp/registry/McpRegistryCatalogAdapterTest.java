package com.sitionix.forgeagent.infrastructure.local.mcp.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.port.McpRegistryCatalog;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;

class McpRegistryCatalogAdapterTest {
    @Test
    void repeatedPageRequestUsesSpringCache() {
        List<String> calls = new ArrayList<>();
        McpRegistryFeignClient client = (search, cursor, limit, version) -> {
            calls.add(search);
            return new McpRegistryFeignClient.Page(List.of(
                    entry("io.example/remote", "streamable-http", "https://example.org/mcp")),
                    new McpRegistryFeignClient.Metadata(null));
        };
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("forge.mcp.enabled", "true")));
            context.register(CacheTestConfiguration.class);
            context.registerBean(CacheManager.class, () -> new ConcurrentMapCacheManager("mcpRegistryPages"));
            context.registerBean(McpRegistryFeignClient.class, () -> client);
            context.registerBean(McpRegistryCatalogAdapter.class);
            context.refresh();

            var catalog = context.getBean(McpRegistryCatalog.class);
            catalog.list("search", null, 20);
            catalog.list("search", null, 20);
            assertThat(calls).containsExactly("search");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class CacheTestConfiguration {}

    @Test
    void decodesOfficialRegistryPageShape() throws Exception {
        String json = """
                {"servers":[{"server":{"name":"io.example/remote","title":"Remote",
                "description":"Search records","version":"1.0.0",
                "remotes":[{"type":"streamable-http","url":"https://example.org/mcp"}]},
                "_meta":{"io.modelcontextprotocol.registry/official":{"status":"active"}}}],
                "metadata":{"nextCursor":"cursor-2","count":1}}
                """;

        var decoded = new ObjectMapper().readValue(json, McpRegistryFeignClient.Page.class);
        var page = new McpRegistryCatalogAdapter((search, cursor, limit, version) -> decoded)
                .list(null, null, 20);

        assertThat(page.servers().getFirst().name()).isEqualTo("io.example/remote");
        assertThat(page.nextCursor()).isEqualTo("cursor-2");
    }

    @Test
    void returnsOnlyRemoteHttpEntriesAndPreservesRegistryCursor() {
        McpRegistryFeignClient client = (search, cursor, limit, version) -> {
            assertThat(search).isEqualTo("search");
            assertThat(cursor).isNull();
            assertThat(limit).isEqualTo(20);
            assertThat(version).isEqualTo("latest");
            return new McpRegistryFeignClient.Page(
                List.of(
                        entry("io.example/remote", "streamable-http", "https://example.org/mcp"),
                        entry("io.example/local", "stdio", "local"),
                        entry("io.example/legacy", "sse", "https://example.org/sse"),
                        entry("io.example/unsafe", "streamable-http", "javascript:alert(1)")),
                new McpRegistryFeignClient.Metadata("cursor-2"));
        };

        var page = new McpRegistryCatalogAdapter(client).list("search", null, 20);

        assertThat(page.servers()).extracting(server -> server.name()).containsExactly("io.example/remote");
        assertThat(page.servers().getFirst().endpoint()).isEqualTo("https://example.org/mcp");
        assertThat(page.nextCursor()).isEqualTo("cursor-2");
    }

    private static McpRegistryFeignClient.Entry entry(String name, String type, String url) {
        return new McpRegistryFeignClient.Entry(new McpRegistryFeignClient.Server(
                name, name, "description", "1.0.0", List.of(new McpRegistryFeignClient.Remote(type, url))));
    }
}
