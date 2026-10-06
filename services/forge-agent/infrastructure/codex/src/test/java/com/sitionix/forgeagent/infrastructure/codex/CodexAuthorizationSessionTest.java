package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.port.LlmAuthorizationGateway;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodexAuthorizationSessionTest {
    final ObjectMapper mapper = new ObjectMapper();
    final FakeCodexProcess process = new FakeCodexProcess();

    @Test void cached_account_read_uses_supported_schema_and_no_refresh() throws Exception {
        try (var session = open()) {
            var pending = CompletableFuture.supplyAsync(() -> session.readAccount(false));
            var request = request();
            assertThat(request.path("method").asText()).isEqualTo("account/read");
            assertThat(request.path("params").toString()).isEqualTo("{\"refreshToken\":false}");
            reply(request, "{\"account\":null,\"requiresOpenaiAuth\":true}");
            assertThat(pending.get(2, TimeUnit.SECONDS).connected()).isFalse();
        }
        assertThat(process.isAlive()).isFalse();
    }

    @Test void malformed_and_non_chatgpt_accounts_are_rejected_without_payloads() throws Exception {
        for (String result : List.of("{}", "{\"requiresOpenaiAuth\":true,\"account\":{\"type\":\"apiKey\"}}",
                "{\"requiresOpenaiAuth\":true,\"account\":{\"type\":\"chatgpt\",\"email\":42,\"planType\":\"plus\"}}")) {
            // One transport can continue after response validation rejects a malformed result.
            try (var fixture = new CodexAuthorizationSessionTest().opened()) {
                var pending = CompletableFuture.supplyAsync(() -> fixture.session.readAccount(false));
                fixture.owner.reply(fixture.owner.request(), result);
                assertThatThrownBy(() -> pending.get(2, TimeUnit.SECONDS)).hasRootCauseMessage("CODEX_AUTH_PROTOCOL_ERROR");
            }
        }
    }

    @Test void managed_browser_start_validates_url_and_redacts_to_string() throws Exception {
        try (var session = open()) {
            var pending = CompletableFuture.supplyAsync(session::startLogin);
            var request = request();
            assertThat(request.path("method").asText()).isEqualTo("account/login/start");
            assertThat(request.path("params").toString()).isEqualTo("{\"type\":\"chatgpt\"}");
            reply(request, "{\"type\":\"chatgpt\",\"loginId\":\"id-1\",\"authUrl\":\"https://auth.openai.com/oauth/authorize?state=secret\"}");
            var login = pending.get(2, TimeUnit.SECONDS);
            assertThat(login.authUrl()).startsWith("https://auth.openai.com/");
            assertThat(login.toString()).doesNotContain("secret", "authUrl", "https:");
        }
    }

    @Test void unsafe_auth_urls_are_rejected() throws Exception {
        for (String url : List.of("http://auth.openai.com/a", "https://auth.openai.com.evil.test/a",
                "https://user:secret@auth.openai.com/a", "https://auth.openai.com:444/a", "javascript:secret")) {
            try (var fixture = new CodexAuthorizationSessionTest().opened()) {
                var pending = CompletableFuture.supplyAsync(fixture.session::startLogin);
                fixture.owner.reply(fixture.owner.request(), "{\"type\":\"chatgpt\",\"loginId\":\"id-1\",\"authUrl\":\"" + url + "\"}");
                assertThatThrownBy(() -> pending.get(2, TimeUnit.SECONDS)).hasRootCauseMessage("CODEX_AUTH_PROTOCOL_ERROR");
            }
        }
    }

    @Test void notifications_are_narrow_safe_and_do_not_block_response_reader() throws Exception {
        try (var session = open()) {
            process.writeStdout("{\"method\":\"account/login/completed\",\"params\":{\"loginId\":\"id-1\",\"success\":false,\"error\":\"private-token\"}}");
            process.writeStdout("{\"method\":\"account/updated\",\"params\":{\"authMode\":\"chatgpt\",\"planType\":\"plus\"}}");
            process.writeStdout("{\"method\":\"turn/started\",\"params\":{\"secret\":\"private-token\"}}");
            var barrier = CompletableFuture.supplyAsync(() -> session.readAccount(false));
            reply(request(), "{\"account\":null,\"requiresOpenaiAuth\":true}");
            barrier.get(2, TimeUnit.SECONDS);
            assertThat(session.drainEvents()).containsExactly(LlmAuthorizationGateway.Event.completed("id-1", false), LlmAuthorizationGateway.Event.updated());
        }
    }

    @Test void cancel_and_logout_use_only_supported_methods() throws Exception {
        try (var session = open()) {
            var cancel = CompletableFuture.runAsync(() -> session.cancelLogin("id-1"));
            var request = request();
            assertThat(request.path("method").asText()).isEqualTo("account/login/cancel");
            assertThat(request.path("params").toString()).isEqualTo("{\"loginId\":\"id-1\"}");
            reply(request, "{\"status\":\"canceled\"}");
            cancel.get(2, TimeUnit.SECONDS);
            var logout = CompletableFuture.runAsync(session::logout);
            request = request();
            assertThat(request.path("method").asText()).isEqualTo("account/logout");
            reply(request, "{}");
            logout.get(2, TimeUnit.SECONDS);
        }
    }

    @Test void account_updated_accepts_schema_optional_fields_and_rejects_malformed_types() throws Exception {
        try (var session = open()) {
            process.writeStdout("{\"method\":\"account/updated\",\"params\":{}}");
            var barrier = CompletableFuture.supplyAsync(() -> session.readAccount(false));
            reply(request(), "{\"account\":null,\"requiresOpenaiAuth\":true}");
            barrier.get(2, TimeUnit.SECONDS);
            assertThat(session.drainEvents()).containsExactly(LlmAuthorizationGateway.Event.updated());
            process.writeStdout("{\"method\":\"account/updated\",\"params\":{\"authMode\":42}}");
            barrier = CompletableFuture.supplyAsync(() -> session.readAccount(false));
            reply(request(), "{\"account\":null,\"requiresOpenaiAuth\":true}");
            barrier.get(2, TimeUnit.SECONDS);
            assertThat(session.drainEvents()).containsExactly(LlmAuthorizationGateway.Event.failed());
        }
    }

    @Test void remote_error_is_redacted_at_provider_boundary() throws Exception {
        try (var session = open()) {
            var pending = CompletableFuture.supplyAsync(() -> session.readAccount(true));
            var request = request();
            assertThat(request.path("params").path("refreshToken").asBoolean()).isTrue();
            process.writeStdout("{\"id\":\"" + request.path("id").asText() + "\",\"error\":{\"code\":401,\"message\":\"private-token https://auth.openai.com?state=secret\"}}");
            assertThatThrownBy(() -> pending.get(2, TimeUnit.SECONDS)).hasRootCauseMessage("CODEX_AUTH_PROVIDER_ERROR");
        }
    }

    CodexAuthorizationSession open() throws Exception {
        var pending = CompletableFuture.supplyAsync(() -> {
            var session = new CodexAuthorizationSession(mapper,
                    new StartedCodexAppServer(process, List.of("codex", "app-server"), Instant.now()), properties());
            session.initialize();
            return session;
        });
        var initialize = request();
        assertThat(initialize.path("method").asText()).isEqualTo("initialize");
        reply(initialize, "{\"userAgent\":\"codex-cli/0.160.0\"}");
        assertThat(request().path("method").asText()).isEqualTo("initialized");
        return pending.get(2, TimeUnit.SECONDS);
    }

    Fixture opened() throws Exception { return new Fixture(this, open()); }
    record Fixture(CodexAuthorizationSessionTest owner, CodexAuthorizationSession session) implements AutoCloseable {
        public void close() { session.close(); }
    }
    JsonNode request() throws Exception {
        return mapper.readTree(CompletableFuture.supplyAsync(process::readRequest).get(2, TimeUnit.SECONDS));
    }
    void reply(JsonNode request, String result) { process.writeStdout("{\"id\":\"" + request.path("id").asText() + "\",\"result\":" + result + "}"); }
    static CodexAppServerProperties properties() {
        var properties = new CodexAppServerProperties();
        properties.setRequestTimeout(Duration.ofSeconds(2));
        properties.setGracefulTerminateTimeout(Duration.ofMillis(10));
        properties.setForceKillTimeout(Duration.ofMillis(10));
        return properties;
    }
}
