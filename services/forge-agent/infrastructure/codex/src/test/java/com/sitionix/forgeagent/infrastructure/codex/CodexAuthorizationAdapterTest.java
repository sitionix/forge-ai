package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class CodexAuthorizationAdapterTest {
    @TempDir Path root;

    @Test void auth_process_uses_neutral_managed_routing_workspace_without_grants() throws Exception {
        var mapper = new ObjectMapper();
        var properties = CodexAuthorizationSessionTest.properties();
        properties.setRuntimeCwd(root.resolve("neutral").toString());
        var process = new FakeCodexProcess();
        var cwd = new AtomicReference<Path>();
        CodexAppServerProcessStarter starter = path -> {
            cwd.set(path);
            return new StartedCodexAppServer(process, List.of("codex", "app-server"), Instant.now());
        };
        var adapter = new CodexAuthorizationAdapter(mapper, starter, properties, new CodexRuntimeWorkspace(properties));
        var opening = CompletableFuture.supplyAsync(adapter::openSession);
        var request = mapper.readTree(CompletableFuture.supplyAsync(process::readRequest).get(2, TimeUnit.SECONDS));
        process.writeStdout("{\"id\":\"" + request.path("id").asText() + "\",\"result\":{\"userAgent\":\"codex-cli/0.160.0\"}}");
        CompletableFuture.supplyAsync(process::readRequest).get(2, TimeUnit.SECONDS);
        try (var session = opening.get(2, TimeUnit.SECONDS)) {
            assertThat(cwd.get()).isEqualTo(root.resolve("neutral"));
            assertThat(session.healthy()).isTrue();
        }
        assertThat(process.isAlive()).isFalse();
    }

    @Test void failed_initialization_retains_unclean_process_and_blocks_replacement() throws Exception {
        var mapper = new ObjectMapper();
        var properties = CodexAuthorizationSessionTest.properties();
        properties.setRuntimeCwd(root.resolve("neutral").toString());
        var process = new FakeCodexProcess(false, false);
        var starts = new AtomicInteger();
        var adapter = new CodexAuthorizationAdapter(mapper, path -> {
            starts.incrementAndGet();
            return new StartedCodexAppServer(process, List.of("codex", "app-server"), Instant.now());
        }, properties, new CodexRuntimeWorkspace(properties));
        try {
            var opening = CompletableFuture.supplyAsync(adapter::openSession);
            var request = mapper.readTree(CompletableFuture.supplyAsync(process::readRequest).get(2, TimeUnit.SECONDS));
            process.writeStdout("{\"id\":\"" + request.path("id").asText() + "\",\"result\":{}}");
            assertThatThrownBy(() -> opening.get(2, TimeUnit.SECONDS)).hasRootCauseMessage("CODEX_AUTH_UNAVAILABLE");
            assertThatThrownBy(adapter::openSession).hasMessage("CODEX_AUTH_UNAVAILABLE");
            assertThat(starts.get()).isEqualTo(1);
        } finally { process.terminateNow(); }
    }
}
