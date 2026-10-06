package com.sitionix.forgeagent.infrastructure.local.mcp.oauth;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.exception.McpOAuthException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.infrastructure.local.mcp.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ssl.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class McpOAuthHttpConfigurationTest {
    @TempDir Path temporary;

    @Test void zeroAndNegativeDurationsFailConfiguration() {
        for (String property : List.of("connect-timeout", "read-timeout", "transaction-ttl"))
            for (String duration : List.of("0s", "-1s"))
                assertThatThrownBy(() -> context(Map.of("forge.mcp.oauth." + property, duration), null))
                        .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test void callbackCannotBeTakenFromUntrustedUrl() {
        for (String callback : List.of("http://user:secret@localhost/callback", "javascript:alert(1)", "https://example.org/callback?token=secret", "https://example.org/callback#fragment"))
            assertThatThrownBy(() -> context(Map.of("forge.mcp.oauth.callback-uri", callback), null))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test void configuredReadTimeoutBoundsUnresponsiveTokenEndpoint() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try (var context = context(Map.of("forge.mcp.oauth.read-timeout", "100ms", "forge.mcp.probe.allowed-private-endpoints", "127.0.0.1:" + server.getAddress().getPort()), null)) {
            var config = configuration("http://127.0.0.1:" + server.getAddress().getPort());
            assertThatThrownBy(() -> context.getBean(McpOAuthClient.class).exchange(config, new McpOAuthCredentials("client-canary", null), "code", "verifier"))
                    .isInstanceOf(McpOAuthException.class).hasMessage("OAuth provider is unavailable.");
            assertThat(entered.getCount()).isZero();
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void sslBundleTrustIsUsedByProductionTokenClient() throws Exception {
        Path storeFile = temporary.resolve("fixture.p12");
        String password = "synthetic-password";
        var keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keystore", storeFile.toString(),
                "-storetype", "PKCS12", "-storepass", password, "-keypass", password, "-dname", "CN=127.0.0.1",
                "-ext", "SAN=ip:127.0.0.1", "-validity", "1", "-noprompt")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try { assertThat(keytool.waitFor(30, TimeUnit.SECONDS)).isTrue(); assertThat(keytool.exitValue()).isZero(); }
        finally { if (keytool.isAlive()) keytool.destroyForcibly(); }
        var keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(storeFile)) { keys.load(input, password.toCharArray()); }
        var trust = KeyStore.getInstance("PKCS12"); trust.load(null, null); trust.setCertificateEntry("fixture", keys.getCertificate("fixture"));
        var bundle = SslBundle.of(SslStoreBundle.of(keys, password, trust));
        var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(bundle.createSslContext()));
        server.createContext("/token", exchange -> {
            byte[] body = "{\"access_token\":\"tls-canary\",\"token_type\":\"bearer\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        String address = "https://127.0.0.1:" + server.getAddress().getPort();
        Map<String, String> allowed = Map.of("forge.mcp.probe.allowed-private-endpoints", "127.0.0.1:" + server.getAddress().getPort());
        try {
            try (var noBundle = context(allowed, null)) {
                assertThatThrownBy(() -> noBundle.getBean(McpOAuthClient.class).exchange(configuration(address), new McpOAuthCredentials("client", null), "code", "verifier"))
                        .isInstanceOf(McpOAuthException.class);
            }
            var properties = new HashMap<>(allowed); properties.put("forge.mcp.oauth.ssl-bundle", "fixture");
            try (var configured = context(properties, new DefaultSslBundleRegistry("fixture", SslBundle.of(SslStoreBundle.of(null, null, trust))))) {
                assertThat(configured.getBean(McpOAuthClient.class).exchange(configuration(address), new McpOAuthCredentials("client", null), "code", "verifier").accessToken())
                        .isEqualTo("tls-canary");
            }
        } finally { server.stop(0); }
    }

    private static McpOAuthConfiguration configuration(String address) {
        URI base = URI.create(address);
        return new McpOAuthConfiguration(base, base.resolve("/authorize"), base.resolve("/token"), null,
                "fixture", "client_secret_post", Set.of("tools"), URI.create("https://fixture.example/mcp"));
    }
    private static AnnotationConfigApplicationContext context(Map<String, String> properties, SslBundles bundles) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", new HashMap<String, Object>(properties)));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(McpCredentialCipher.class, () -> new AesGcmMcpCredentialCipher(() -> new McpLocalKeys("fixture", Map.of("fixture", new byte[32]))));
        if (bundles != null) context.registerBean(SslBundles.class, () -> bundles);
        context.register(McpOAuthHttpConfiguration.class);
        try { context.refresh(); } catch (RuntimeException failure) { context.close(); throw failure; }
        return context;
    }
}
