package com.sitionix.forgeai.infrastructure.knowledgeclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayErrorCode;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayException;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSearchRequest;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class HttpKnowledgeGatewayTest {

    @Test
    void statusProxyMapsSuccess() {
        final HttpKnowledgeGateway gateway = gateway(new FakeHttpClient(200, """
                {"status":"UP","module":"knowledge","catalog":{"configured":true,"type":"service_catalog"},"inventory":{"implemented":true,"sourceCount":1,"fileCount":2},"search":{"implemented":true,"mode":"keyword"},"vectorStore":{"implemented":false,"enabled":false},"rag":{"implemented":false,"enabled":false}}
                """));

        final var status = gateway.status();

        assertThat(status.status()).isEqualTo("UP");
        assertThat(status.catalog().configured()).isTrue();
        assertThat(status.search().get("mode")).isEqualTo("keyword");
    }

    @Test
    void sourcesProxyMapsSuccess() {
        final HttpKnowledgeGateway gateway = gateway(new FakeHttpClient(200, """
                {"catalog":{"configured":true,"type":"service_catalog"},"sources":[{"sourceId":"svc","displayName":"Service","group":"backend","path":"svc","rootExists":true,"tags":["java"],"domainKeywords":[],"ownsBusinessAreas":[],"tests":[]}],"diagnostics":[]}
                """));

        final var sources = gateway.sources();

        assertThat(sources.sources()).hasSize(1);
        assertThat(sources.sources().getFirst().sourceId()).isEqualTo("svc");
    }

    @Test
    void inventoryBuildProxyMapsSuccess() {
        final HttpKnowledgeGateway gateway = gateway(new FakeHttpClient(200, """
                {"status":"COMPLETED","sourceCount":1,"fileCount":3,"skippedCount":2,"startedAt":"a","completedAt":"b"}
                """));

        final var result = gateway.buildInventory(new KnowledgeInventoryBuildRequest(List.of(), List.of(), false));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.fileCount()).isEqualTo(3);
    }

    @Test
    void searchProxyMapsSuccess() {
        final HttpKnowledgeGateway gateway = gateway(new FakeHttpClient(200, """
                {"query":"JarvisGateway","results":[{"sourceId":"svc","displayName":"Service","relativePath":"README.md","lineStart":1,"lineEnd":1,"snippet":"JarvisGateway","matchType":"content","score":1.0}]}
                """));

        final var result = gateway.search(new KnowledgeSearchRequest("JarvisGateway", List.of(), List.of(), 10));

        assertThat(result.results()).hasSize(1);
        assertThat(result.results().getFirst().matchType()).isEqualTo("content");
    }

    @Test
    void connectionFailureMapsToUnavailable() {
        final HttpKnowledgeGateway gateway = gateway(new FakeHttpClient(new ConnectException("refused")));

        assertThatThrownBy(gateway::status)
                .isInstanceOfSatisfying(KnowledgeGatewayException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(KnowledgeGatewayErrorCode.KNOWLEDGE_UNAVAILABLE));
    }

    @Test
    void timeoutMapsToTimeout() {
        final HttpKnowledgeGateway gateway = gateway(new FakeHttpClient(new HttpTimeoutException("timeout")));

        assertThatThrownBy(gateway::status)
                .isInstanceOfSatisfying(KnowledgeGatewayException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(KnowledgeGatewayErrorCode.KNOWLEDGE_TIMEOUT));
    }

    @Test
    void invalidJsonMapsToBadResponse() {
        final HttpKnowledgeGateway gateway = gateway(new FakeHttpClient(200, "not-json"));

        assertThatThrownBy(gateway::status)
                .isInstanceOfSatisfying(KnowledgeGatewayException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(KnowledgeGatewayErrorCode.KNOWLEDGE_BAD_RESPONSE));
    }

    @Test
    void blankSearchRejectedBeforeProxying() {
        final FakeHttpClient client = new FakeHttpClient(200, "{}");
        final HttpKnowledgeGateway gateway = gateway(client);

        assertThatThrownBy(() -> gateway.search(new KnowledgeSearchRequest(" ", List.of(), List.of(), 10)))
                .isInstanceOfSatisfying(KnowledgeGatewayException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(KnowledgeGatewayErrorCode.SEARCH_QUERY_INVALID));
        assertThat(client.calls).isZero();
    }

    private static HttpKnowledgeGateway gateway(final HttpClient client) {
        final KnowledgeClientProperties properties = new KnowledgeClientProperties();
        properties.setBaseUrl(URI.create("http://127.0.0.1:7081"));
        return new HttpKnowledgeGateway(new ObjectMapper(), properties, client, new KnowledgeHttpErrorMapper());
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
            return HttpHeaders.of(java.util.Map.of(), (left, right) -> true);
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
