package com.sitionix.forgeagent.infrastructure.local.mcp.protocol;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.*;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/** Narrow SDK seam: retain HTTP 401/403 as typed status without reading or logging response bodies. */
final class McpProbeHttpClientBuilder implements HttpClient.Builder {
    private final HttpClient.Builder delegate = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY);
    private volatile int authStatus;
    private volatile boolean completedSuccessfulPost;
    private volatile boolean sawSuccessfulPost;

    int authStatus() { return authStatus; }
    boolean completedSuccessfulPost() { return completedSuccessfulPost; }
    boolean sawSuccessfulPost() { return sawSuccessfulPost; }

    static final class AuthStatus extends RuntimeException {
        AuthStatus(int status) { super("MCP authentication status " + status); }
    }

    @Override public HttpClient.Builder cookieHandler(CookieHandler value) { delegate.cookieHandler(value); return this; }
    @Override public HttpClient.Builder connectTimeout(Duration value) { delegate.connectTimeout(value); return this; }
    @Override public HttpClient.Builder sslContext(SSLContext value) { delegate.sslContext(value); return this; }
    @Override public HttpClient.Builder sslParameters(SSLParameters value) { delegate.sslParameters(value); return this; }
    @Override public HttpClient.Builder executor(Executor value) { delegate.executor(value); return this; }
    @Override public HttpClient.Builder followRedirects(HttpClient.Redirect value) {
        if (value != HttpClient.Redirect.NEVER) throw new IllegalArgumentException("MCP redirects are forbidden");
        return this;
    }
    @Override public HttpClient.Builder version(HttpClient.Version value) { delegate.version(value); return this; }
    @Override public HttpClient.Builder priority(int value) { delegate.priority(value); return this; }
    @Override public HttpClient.Builder proxy(ProxySelector value) { delegate.proxy(value); return this; }
    @Override public HttpClient.Builder authenticator(Authenticator value) { delegate.authenticator(value); return this; }
    @Override public HttpClient build() { return new StatusClient(delegate.build()); }

    private final class StatusClient extends HttpClient {
        private final HttpClient delegate;
        private StatusClient(HttpClient delegate) { this.delegate = delegate; }
        @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
        @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
        @Override public Redirect followRedirects() { return delegate.followRedirects(); }
        @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
        @Override public SSLContext sslContext() { return delegate.sslContext(); }
        @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
        @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
        @Override public Version version() { return delegate.version(); }
        @Override public Optional<Executor> executor() { return delegate.executor(); }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            return checked(delegate.send(request, observe(request, handler)));
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return delegate.sendAsync(request, observe(request, handler)).thenApply(this::checked);
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                          HttpResponse.PushPromiseHandler<T> push) {
            return delegate.sendAsync(request, observe(request, handler), push).thenApply(this::checked);
        }
        @Override public WebSocket.Builder newWebSocketBuilder() { return delegate.newWebSocketBuilder(); }
        @Override public void close() { delegate.close(); }
        private <T> HttpResponse.BodyHandler<T> observe(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return info -> {
                if (request.method().equals("POST") && info.statusCode() >= 200 && info.statusCode() < 300) {
                    sawSuccessfulPost = true;
                }
                return handler.apply(info);
            };
        }
        private <T> HttpResponse<T> checked(HttpResponse<T> response) {
            if (response.statusCode() >= 200 && response.statusCode() < 300
                    && response.request().method().equals("POST"))
                completedSuccessfulPost = true;
            if (response.request().method().equals("POST")
                    && (response.statusCode() == 401 || response.statusCode() == 403)) {
                authStatus = response.statusCode();
                throw new AuthStatus(response.statusCode());
            }
            return response;
        }
    }
}
