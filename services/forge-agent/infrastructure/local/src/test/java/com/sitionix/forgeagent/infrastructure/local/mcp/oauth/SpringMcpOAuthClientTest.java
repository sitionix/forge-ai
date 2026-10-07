package com.sitionix.forgeagent.infrastructure.local.mcp.oauth;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.exception.McpOAuthException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.McpOAuthClient;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class SpringMcpOAuthClientTest {
    private HttpServer server;
    private AnnotationConfigApplicationContext context;
    private final ArrayBlockingQueue<Request> requests = new ArrayBlockingQueue<>(16);
    private final ArrayBlockingQueue<Response> responses = new ArrayBlockingQueue<>(16);
    private McpOAuthClient client;
    private URI base;
    private static final URI CALLBACK = URI.create("http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/agents/integrations/mcp/oauth/callback");
    private static final URI RESOURCE = URI.create("https://fixture.example/mcp");

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                requests.add(new Request(exchange.getRequestURI().getPath(),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                        exchange.getRequestHeaders().getFirst("Authorization")));
                Response response = responses.poll(3, TimeUnit.SECONDS);
                if (response == null) { exchange.sendResponseHeaders(500, -1); return; }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (response.location() != null) exchange.getResponseHeaders().set("Location", response.location());
                byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(response.status(), body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                "forge.mcp.probe.allowed-private-endpoints", "127.0.0.1:" + server.getAddress().getPort(),
                "forge.mcp.oauth.read-timeout", "2s")));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(com.sitionix.forgeagent.domain.port.McpCredentialCipher.class,
                () -> new com.sitionix.forgeagent.infrastructure.local.mcp.AesGcmMcpCredentialCipher(
                    () -> new com.sitionix.forgeagent.infrastructure.local.mcp.McpLocalKeys("fixture", Map.of("fixture", new byte[32]))));
        context.register(McpOAuthHttpConfiguration.class);
        context.refresh();
        client = context.getBean(McpOAuthClient.class);
    }

    @AfterEach void stop() { if (context != null) context.close(); if (server != null) server.stop(0); }

    @Test void codeExchangeSendsPkceResourceAndFixedRedirect() throws Exception {
        var authorization = client.authorization(configuration(), CALLBACK, "synthetic-state");
        Map<String, String> query = form(authorization.authorizationUrl().getRawQuery());
        assertThat(query).containsEntry("code_challenge_method", "S256")
                .containsEntry("state", "synthetic-state").containsEntry("resource", RESOURCE.toString())
                .containsEntry("redirect_uri", CALLBACK.toString());
        assertThat(query.get("code_challenge")).isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(authorization.verifier().getBytes(StandardCharsets.US_ASCII))));
        reply(200, "{\"access_token\":\"access-canary\",\"token_type\":\"bearer\",\"expires_in\":3600,\"scope\":\"tools\"}");
        var tokens = client.exchange(configuration(), new McpOAuthCredentials("client-canary", null), "code-canary", authorization.verifier());
        Request request = requests.remove();
        assertThat(request.path()).isEqualTo("/token");
        assertThat(form(request.body())).containsEntry("grant_type", "authorization_code")
                .containsEntry("client_secret", "client-canary").containsEntry("code", "code-canary")
                .containsEntry("code_verifier", authorization.verifier()).containsEntry("redirect_uri", CALLBACK.toString())
                .containsEntry("resource", RESOURCE.toString());
        assertThat(tokens.accessToken()).isEqualTo("access-canary");
        assertThat(tokens.expiresAt()).isAfter(Instant.now().plusSeconds(3500));
        assertThat(tokens.grantedScopes()).containsExactly("tools");
        assertThat(tokens.toString()).doesNotContain("access-canary");
        assertThat(new ObjectMapper().writeValueAsString(Map.of("url", authorization.authorizationUrl()))).doesNotContain(authorization.verifier());
    }

    @Test void refreshRotatesTokens() {
        reply(200, "{\"access_token\":\"rotated-access\",\"refresh_token\":\"rotated-refresh\",\"token_type\":\"bearer\",\"expires_in\":60}");
        var tokens = client.refresh(configuration(), credentials());
        assertThat(form(requests.remove().body())).containsEntry("grant_type", "refresh_token")
                .containsEntry("refresh_token", "old-refresh").containsEntry("resource", RESOURCE.toString());
        assertThat(tokens.refreshToken()).isEqualTo("rotated-refresh");
        assertThat(tokens.accessToken()).isEqualTo("rotated-access");
    }

    @Test void publicClientRefreshIdentifiesClientWithoutSendingASecret() {
        var publicClient = new McpOAuthConfiguration(base, base.resolve("/authorize"), base.resolve("/token"),
                null, "public-client", "none", Set.of("tools"), RESOURCE);
        reply(200, "{\"access_token\":\"rotated-access\",\"refresh_token\":\"rotated-refresh\",\"token_type\":\"bearer\",\"expires_in\":60}");
        var tokens = client.refresh(publicClient, new McpOAuthCredentials(null, credentials().tokens()));
        var request = requests.remove();
        assertThat(form(request.body())).containsEntry("client_id", "public-client")
                .containsEntry("grant_type", "refresh_token").containsEntry("refresh_token", "old-refresh")
                .containsEntry("resource", RESOURCE.toString()).doesNotContainKey("client_secret");
        assertThat(request.authorization()).isNull();
        assertThat(tokens.refreshToken()).isEqualTo("rotated-refresh");
    }

    @Test void unauthorizedOAuthErrorRequiresReconnectWithoutLeakingProviderDetails() {
        for (String error : List.of("invalid_client", "invalid_grant")) {
            reply(401, "{\"error\":\"" + error + "\",\"error_description\":\"client-canary refresh-canary\"}");
            assertThatThrownBy(() -> client.refresh(configuration(), credentials())).isInstanceOf(McpOAuthException.class)
                    .hasMessage("OAuth authorization requires reconnect.").hasNoCause();
        }
    }

    @Test void malformedUnauthorizedBodyDoesNotInvalidateStoredCredentials() {
        reply(401, "broken-provider-canary");
        assertThatThrownBy(() -> client.refresh(configuration(), credentials())).isInstanceOf(McpOAuthException.class)
                .hasMessage("OAuth provider returned an invalid response.").hasNoCause();
    }

    @Test void missingExpiryRemainsUnknown() {
        reply(200, "{\"access_token\":\"token\",\"token_type\":\"bearer\"}");
        var tokens = exchange();
        assertThat(tokens.expiresAt()).isNull();
        assertThat(tokens.refreshExpiresAt()).isNull();
        assertThat(tokens.grantedScopes()).isNull();
        assertThat(tokens.refreshToken()).isNull();
    }

    @Test void zeroExpiryIsNotUnknown() {
        reply(200, "{\"access_token\":\"token\",\"token_type\":\"bearer\",\"expires_in\":0}");
        assertThat(exchange().expiresAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test void malformedExpiryFailsSafely() {
        for (String value : List.of("-1", "\"expiry-canary\"", "null", "1.2")) {
            reply(200, "{\"access_token\":\"token-canary\",\"token_type\":\"bearer\",\"expires_in\":" + value + "}");
            assertThatThrownBy(this::exchange).isInstanceOf(McpOAuthException.class)
                    .hasMessageNotContaining("canary").hasNoCause();
        }
    }

    @Test void malformedOptionalTokenMetadataFailsBeforeItCanReplaceUsableCredentials() {
        for(String field:List.of("\"refresh_token\":{\"secret-canary\":1}","\"refresh_token\":123","\"refresh_token\":\"\"",
                "\"scope\":[\"scope-canary\"]","\"scope\":123","\"scope\":null")) {
            reply(200,"{\"access_token\":\"access-canary\",\"token_type\":\"bearer\","+field+"}");
            assertThatThrownBy(()->client.refresh(configuration(),credentials())).isInstanceOf(McpOAuthException.class)
                .hasMessageNotContaining("canary").hasNoCause();
        }
    }
    @Test void overflowingRefreshExpiryFailsAtSafeOAuthBoundary() {
        reply(200,"{\"access_token\":\"access-canary\",\"refresh_token\":\"refresh-canary\",\"token_type\":\"bearer\",\"refresh_token_expires_in\":9223372036854775807}");
        assertThatThrownBy(this::exchange).isInstanceOf(McpOAuthException.class).hasNoCause().hasMessageNotContaining("canary");
    }
    @Test void omittedRefreshDoesNotEraseExistingRefreshOnRotation() {
        reply(200, "{\"access_token\":\"new-token\",\"token_type\":\"bearer\"}");
        assertThat(client.refresh(configuration(), credentials()).refreshToken()).isEqualTo("old-refresh");
    }

    @Test void providerErrorDoesNotLeakCanary() {
        reply(400, "{\"error\":\"invalid_grant\",\"error_description\":\"client-canary refresh-canary code-canary\"}");
        assertThatThrownBy(() -> client.refresh(configuration(), credentials())).isInstanceOf(McpOAuthException.class)
                .hasMessage("OAuth authorization requires reconnect.").hasNoCause();
    }

    @Test void redirectDoesNotReceiveClientSecrets() {
        responses.add(new Response(302, "{\"access_token\":\"redirect-canary\",\"token_type\":\"bearer\"}", base + "/destination"));
        assertThatThrownBy(this::exchange).isInstanceOf(McpOAuthException.class);
        assertThat(requests).hasSize(1);
        assertThat(requests.remove().path()).isEqualTo("/token");
    }

    @Test void endpointDeniedBeforeSendingSecret() {
        var denied = new McpOAuthConfiguration(base, base.resolve("/authorize"), URI.create("http://127.0.0.1:1/token"),
                null, "fixture-client", "client_secret_post", Set.of("tools"), RESOURCE);
        assertThatThrownBy(() -> client.exchange(denied, credentials(), "code", "verifier"))
                .isInstanceOf(McpOAuthException.class).hasMessageNotContaining("canary");
        assertThat(requests).isEmpty();
    }

    @Test void frameworkDebugLogsDoNotContainOAuthSecrets() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.springframework.web.client.RestTemplate.class);
        var previousLevel = logger.getLevel(); boolean previousAdditive = logger.isAdditive();
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender); logger.setLevel(ch.qos.logback.classic.Level.DEBUG); logger.setAdditive(false);
        try {
            reply(200, "{\"access_token\":\"access-canary\",\"token_type\":\"bearer\"}");
            exchange();
            assertThat(appender.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains("client-canary") || message.contains("verifier") && message.contains("=[verifier]") || message.contains("access-canary"));
        } finally { logger.detachAppender(appender); logger.setLevel(previousLevel); logger.setAdditive(previousAdditive); appender.stop(); }
    }

    @Test void overflowingExpiryFailsSafely() {
        reply(200, "{\"access_token\":\"access-canary\",\"token_type\":\"bearer\",\"expires_in\":9223372036854775807}");
        assertThatThrownBy(this::exchange).isInstanceOf(McpOAuthException.class).hasNoCause();
    }

    @Test void emptyOrMalformedTokenBodyFailsSafely() {
        for (String body : List.of("", "broken-response-canary", "[]", "{\"token_type\":\"bearer\"}")) {
            reply(200, body);
            assertThatThrownBy(this::exchange).isInstanceOf(McpOAuthException.class).hasNoCause().hasMessageNotContaining("canary");
        }
    }

    @Test void configuredClientIgnoresJvmProxySelector() {
        var previous = ProxySelector.getDefault();
        var selections = new ArrayBlockingQueue<URI>(2);
        try {
            ProxySelector.setDefault(new ProxySelector() {
                public List<Proxy> select(URI uri) { selections.add(uri); return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 1))); }
                public void connectFailed(URI uri, java.net.SocketAddress address, java.io.IOException failure) { }
            });
            reply(200, "{\"access_token\":\"access-canary\",\"token_type\":\"bearer\"}");
            assertThat(exchange().accessToken()).isEqualTo("access-canary");
            assertThat(selections).isEmpty();
        } finally { ProxySelector.setDefault(previous); }
    }


    private McpOAuthTokens exchange() { return client.exchange(configuration(), new McpOAuthCredentials("client-canary", null), "code", "verifier"); }
    private McpOAuthCredentials credentials() { return new McpOAuthCredentials("client-canary",
            new McpOAuthTokens("old-access", "old-refresh", Instant.now().minusSeconds(10), null, Set.of("tools"))); }
    private McpOAuthConfiguration configuration() { return new McpOAuthConfiguration(base, base.resolve("/authorize"), base.resolve("/token"),
            null, "fixture-client", "client_secret_post", Set.of("tools"), RESOURCE); }
    private void reply(int status, String body) { responses.add(new Response(status, body, null)); }
    private static Map<String, String> form(String value) {
        Map<String, String> values = new HashMap<>();
        for (String part : value.split("&")) {
            String[] pair = part.split("=", 2);
            values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return values;
    }
    private record Request(String path, String body, String authorization) {}
    private record Response(int status, String body, String location) {}
}
