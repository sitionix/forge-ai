package com.sitionix.forgeai.infrastructure.jarvisclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.infrastructure.jarvis.JarvisCommandRequest;
import com.sitionix.forgeai.application.infrastructure.jarvis.JarvisGatewayErrorCode;
import com.sitionix.forgeai.application.infrastructure.jarvis.JarvisGatewayException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class HttpJarvisGatewayTest {

    @Test
    void statusMapsJarvisStatus() {
        final HttpJarvisGateway gateway = gateway(new FakeHttpClient(200, """
                {"status":"UP","host":"127.0.0.1","port":7071,"model":{"defaultModel":"qwen2.5-coder:7b"},"ollama":{"baseUrl":"http://localhost:11434","status":"UP"},"actions":{"count":2}}
                """));

        final var status = gateway.status();

        assertThat(status.status()).isEqualTo("UP");
        assertThat(status.host()).isEqualTo("127.0.0.1");
        assertThat(status.port()).isEqualTo(7071);
        assertThat(status.model().defaultModel()).isEqualTo("qwen2.5-coder:7b");
        assertThat(status.ollama().status()).isEqualTo("UP");
        assertThat(status.actions().count()).isEqualTo(2);
    }

    @Test
    void actionsDoNotExposeRawCommandArrays() {
        final HttpJarvisGateway gateway = gateway(new FakeHttpClient(200, """
                {"actions":[{"action":"ollama_status","description":"Check Ollama local API","targets":["health"]}]}
                """));

        final var actions = gateway.actions();

        assertThat(actions.actions()).hasSize(1);
        assertThat(actions.actions().getFirst().action()).isEqualTo("ollama_status");
        assertThat(actions.actions().getFirst().targets()).containsExactly("health");
    }

    @Test
    void blankCommandRejectedBeforeProxying() {
        final FakeHttpClient client = new FakeHttpClient(200, "{}");
        final HttpJarvisGateway gateway = gateway(client);

        assertThatThrownBy(() -> gateway.command(new JarvisCommandRequest("   ")))
                .isInstanceOfSatisfying(JarvisGatewayException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(JarvisGatewayErrorCode.INVALID_COMMAND));
        assertThat(client.calls).isZero();
    }

    @Test
    void commandProxiesText() {
        final FakeHttpClient client = new FakeHttpClient(200, """
                {"input":"перевір ollama","intent":{"action":"ollama_status","target":"health","arguments":{}},"execution":{"executed":true,"message":"Action executed: ollama_status.health","output":"Ollama is reachable"}}
                """);
        final HttpJarvisGateway gateway = gateway(client);

        final var response = gateway.command(new JarvisCommandRequest("перевір ollama"));

        assertThat(response.intent().action()).isEqualTo("ollama_status");
        assertThat(response.execution().executed()).isTrue();
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void unsupportedActionMapsToGatewayCode() {
        final HttpJarvisGateway gateway = gateway(new FakeHttpClient(403, """
                {"code":"UNSUPPORTED_ACTION","message":"The requested action is not allowlisted"}
                """));

        assertThatThrownBy(() -> gateway.command(new JarvisCommandRequest("do unsupported thing")))
                .isInstanceOfSatisfying(JarvisGatewayException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(JarvisGatewayErrorCode.UNSUPPORTED_ACTION));
    }

    @Test
    void invalidJsonMapsToBadResponse() {
        final HttpJarvisGateway gateway = gateway(new FakeHttpClient(200, "not-json"));

        assertThatThrownBy(gateway::actions)
                .isInstanceOfSatisfying(JarvisGatewayException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(JarvisGatewayErrorCode.JARVIS_BAD_RESPONSE));
    }

    @Test
    void connectionRefusedMapsToUnavailable() {
        final HttpJarvisGateway gateway = gateway(new FakeHttpClient(new ConnectException("refused")));

        assertThatThrownBy(gateway::status)
                .isInstanceOfSatisfying(JarvisGatewayException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(JarvisGatewayErrorCode.JARVIS_UNAVAILABLE));
    }

    @Test
    void timeoutMapsToTimeout() {
        final HttpJarvisGateway gateway = gateway(new FakeHttpClient(new HttpTimeoutException("timeout")));

        assertThatThrownBy(gateway::status)
                .isInstanceOfSatisfying(JarvisGatewayException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(JarvisGatewayErrorCode.JARVIS_TIMEOUT));
    }

    private static HttpJarvisGateway gateway(final HttpClient client) {
        final JarvisClientProperties properties = new JarvisClientProperties();
        properties.setBaseUrl(URI.create("http://127.0.0.1:7071"));
        return new HttpJarvisGateway(new ObjectMapper(), properties, client);
    }

    private static final class FakeHttpClient extends HttpClient {

        private final int status;
        private final String body;
        private final IOException failure;
        private int calls;

        private FakeHttpClient(final int status, final String body) {
            this.status = status;
            this.body = body;
            this.failure = null;
        }

        private FakeHttpClient(final IOException failure) {
            this.status = 0;
            this.body = null;
            this.failure = failure;
        }

        @Override
        public Optional<java.net.CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(2));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<java.net.ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(final HttpRequest request,
                                        final HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
            this.calls++;
            if (this.failure != null) {
                throw this.failure;
            }
            return new FakeHttpResponse<>(request, this.status, (T) this.body);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                                final HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                                final HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                final HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private record FakeHttpResponse<T>(HttpRequest request, int statusCode, T body) implements HttpResponse<T> {
        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (left, right) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return this.request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
