package com.sitionix.forgeai.api.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.net.ssl.SSLException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class InfrastructureProxyTransport {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final String APPLICATION_JSON = MediaType.APPLICATION_JSON_VALUE;

    private final HttpClient httpClient;
    private final InfrastructureProxyRouteRegistry routeRegistry;
    private final InfrastructureProxyProperties properties;
    private final InfrastructureProxyResponseMapper responseMapper;
    private final ObjectMapper objectMapper;

    public InfrastructureProxyTransport(final HttpClient infrastructureProxyHttpClient,
                                        final InfrastructureProxyRouteRegistry routeRegistry,
                                        final InfrastructureProxyProperties properties,
                                        final InfrastructureProxyResponseMapper responseMapper,
                                        final ObjectMapper objectMapper) {
        this.httpClient = infrastructureProxyHttpClient;
        this.routeRegistry = routeRegistry;
        this.properties = properties;
        this.responseMapper = responseMapper;
        this.objectMapper = objectMapper;
    }

    public CompletableFuture<ResponseEntity<byte[]>> forward(final String routeKey,
                                                             final Map<String, String> pathVariables,
                                                             final byte[] requestBody,
                                                             final org.springframework.http.HttpHeaders incomingHeaders,
                                                             final HttpServletRequest servletRequest) {
        final long startedNanos = System.nanoTime();
        final String correlationId = this.correlationId(incomingHeaders);
        final InfrastructureProxyRoute route;
        try {
            route = this.routeRegistry.require(routeKey);
        } catch (final InfrastructureProxyRouteException exception) {
            return CompletableFuture.completedFuture(this.responseMapper.error(
                    InfrastructureProxyErrorCode.ROUTE_NOT_ALLOWLISTED,
                    "Infrastructure proxy route is not allowlisted.",
                    correlationId,
                    null,
                    exception.route(),
                    HttpStatus.NOT_FOUND,
                    this.elapsedMs(startedNanos),
                    "nexus"
            ));
        }

        final InfrastructureProxyProperties.ServiceProperties service = this.properties.service(route.service());
        if (!service.isEnabled()) {
            return CompletableFuture.completedFuture(this.responseMapper.error(
                    InfrastructureProxyErrorCode.UPSTREAM_UNAVAILABLE,
                    this.serviceLabel(route) + " service is disabled.",
                    correlationId,
                    null,
                    route.key(),
                    HttpStatus.SERVICE_UNAVAILABLE,
                    this.elapsedMs(startedNanos),
                    "nexus"
            ));
        }
        if (!route.requestBodyAllowed() && requestBody != null && requestBody.length > 0) {
            return CompletableFuture.completedFuture(this.responseMapper.error(
                    InfrastructureProxyErrorCode.ROUTE_NOT_ALLOWLISTED,
                    "Infrastructure proxy route does not accept a request body.",
                    correlationId,
                    null,
                    route.key(),
                    HttpStatus.METHOD_NOT_ALLOWED,
                    this.elapsedMs(startedNanos),
                    "nexus"
            ));
        }
        if (requestBody != null && requestBody.length > this.properties.getProxy().getMaxRequestBodyBytes()) {
            return CompletableFuture.completedFuture(this.responseMapper.error(
                    InfrastructureProxyErrorCode.REQUEST_BODY_TOO_LARGE,
                    "Proxy request body exceeds the configured limit.",
                    correlationId,
                    null,
                    route.key(),
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    this.elapsedMs(startedNanos),
                    "nexus"
            ));
        }

        final HttpRequest upstreamRequest = this.upstreamRequest(route, pathVariables, requestBody, incomingHeaders, servletRequest, service, correlationId);
        final CompletableFuture<ResponseEntity<byte[]>> result = new CompletableFuture<>();
        final CompletableFuture<HttpResponse<InputStream>> upstream = this.httpClient.sendAsync(upstreamRequest, HttpResponse.BodyHandlers.ofInputStream());
        result.whenComplete((ignoredResponse, ignoredThrowable) -> {
            if (result.isCancelled()) {
                upstream.cancel(true);
            }
        });
        upstream.whenComplete((response, throwable) -> {
            if (throwable != null) {
                result.complete(this.mapException(throwable, route, correlationId, startedNanos));
                return;
            }
            try {
                result.complete(this.mapResponse(response, route, correlationId, startedNanos));
            } catch (final RuntimeException exception) {
                result.complete(this.mapException(exception, route, correlationId, startedNanos));
            }
        });
        return result;
    }

    private HttpRequest upstreamRequest(final InfrastructureProxyRoute route,
                                        final Map<String, String> pathVariables,
                                        final byte[] requestBody,
                                        final org.springframework.http.HttpHeaders incomingHeaders,
                                        final HttpServletRequest servletRequest,
                                        final InfrastructureProxyProperties.ServiceProperties service,
                                        final String correlationId) {
        final URI uri = service.getBaseUrl().resolve(this.pathAndQuery(route, pathVariables, servletRequest));
        final HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(service.getReadTimeout())
                .header("Accept", APPLICATION_JSON)
                .header(CORRELATION_HEADER, correlationId);
        final String ifNoneMatch = incomingHeaders.getFirst("If-None-Match");
        if (ifNoneMatch != null && !ifNoneMatch.isBlank()) {
            builder.header("If-None-Match", ifNoneMatch);
        }
        if (route.method() == HttpMethod.POST) {
            builder.header("Content-Type", APPLICATION_JSON)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody == null ? new byte[0] : requestBody));
        } else {
            builder.GET();
        }
        return builder.build();
    }

    private String pathAndQuery(final InfrastructureProxyRoute route,
                                final Map<String, String> pathVariables,
                                final HttpServletRequest servletRequest) {
        final String path = route.upstreamPath().apply(pathVariables == null ? Map.of() : pathVariables);
        final String query = servletRequest.getQueryString();
        if (query == null || query.isBlank()) {
            return path;
        }
        return path + "?" + query;
    }

    private ResponseEntity<byte[]> mapResponse(final HttpResponse<InputStream> response,
                                               final InfrastructureProxyRoute route,
                                               final String correlationId,
                                               final long startedNanos) {
        final byte[] body = this.readLimited(response.body());
        if (route.jsonExpected() && !this.isJsonResponse(response.headers(), body)) {
            return this.responseMapper.error(
                    InfrastructureProxyErrorCode.UPSTREAM_INVALID_RESPONSE,
                    this.serviceLabel(route) + " service returned a non-JSON response.",
                    correlationId,
                    response.statusCode(),
                    route.key(),
                    HttpStatus.BAD_GATEWAY,
                    this.elapsedMs(startedNanos),
                    "upstream"
            );
        }
        if (response.statusCode() >= 500) {
            return this.responseMapper.error(
                    InfrastructureProxyErrorCode.UPSTREAM_ERROR,
                    this.serviceLabel(route) + " service returned an upstream error.",
                    correlationId,
                    response.statusCode(),
                    route.key(),
                    HttpStatus.BAD_GATEWAY,
                    this.elapsedMs(startedNanos),
                    "upstream"
            );
        }
        return new ResponseEntity<>(body, this.safeResponseHeaders(response.headers(), correlationId), HttpStatus.valueOf(response.statusCode()));
    }

    private ResponseEntity<byte[]> mapException(final Throwable throwable,
                                                final InfrastructureProxyRoute route,
                                                final String correlationId,
                                                final long startedNanos) {
        final Throwable cause = this.unwrap(throwable);
        if (cause instanceof InfrastructureProxyBodyTooLargeException) {
            return this.responseMapper.error(
                    InfrastructureProxyErrorCode.UPSTREAM_BODY_TOO_LARGE,
                    "Upstream response body exceeds the configured proxy limit.",
                    correlationId,
                    null,
                    route.key(),
                    HttpStatus.BAD_GATEWAY,
                    this.elapsedMs(startedNanos),
                    "nexus"
            );
        }
        if (cause instanceof HttpTimeoutException || cause instanceof java.util.concurrent.TimeoutException) {
            return this.responseMapper.error(
                    InfrastructureProxyErrorCode.UPSTREAM_TIMEOUT,
                    this.serviceLabel(route) + " service did not respond within the configured timeout.",
                    correlationId,
                    HttpStatus.GATEWAY_TIMEOUT.value(),
                    route.key(),
                    HttpStatus.GATEWAY_TIMEOUT,
                    this.elapsedMs(startedNanos),
                    "upstream"
            );
        }
        if (cause instanceof ConnectException || cause instanceof IOException || cause instanceof SSLException) {
            return this.responseMapper.error(
                    InfrastructureProxyErrorCode.UPSTREAM_UNAVAILABLE,
                    this.serviceLabel(route) + " service is unavailable.",
                    correlationId,
                    null,
                    route.key(),
                    HttpStatus.SERVICE_UNAVAILABLE,
                    this.elapsedMs(startedNanos),
                    "upstream"
            );
        }
        return this.responseMapper.error(
                InfrastructureProxyErrorCode.UPSTREAM_ERROR,
                this.serviceLabel(route) + " proxy request failed.",
                correlationId,
                null,
                route.key(),
                HttpStatus.BAD_GATEWAY,
                this.elapsedMs(startedNanos),
                "nexus"
        );
    }

    private long elapsedMs(final long startedNanos) {
        return Math.max(0L, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }

    private byte[] readLimited(final InputStream inputStream) {
        final int limit = this.properties.getProxy().getMaxResponseBodyBytes();
        try (inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192))) {
            final byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new InfrastructureProxyBodyTooLargeException();
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (final IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private boolean isJsonResponse(final HttpHeaders headers, final byte[] body) {
        if (body == null || body.length == 0) {
            return true;
        }
        final String contentType = headers.firstValue("Content-Type").orElse("");
        final String normalized = contentType.toLowerCase(Locale.ROOT);
        if (normalized.contains("application/json") || normalized.endsWith("+json")) {
            return this.isValidJson(body);
        }
        return false;
    }

    private boolean isValidJson(final byte[] body) {
        try {
            this.objectMapper.readTree(body);
            return true;
        } catch (final IOException exception) {
            return false;
        }
    }

    private org.springframework.http.HttpHeaders safeResponseHeaders(final HttpHeaders upstreamHeaders,
                                                                    final String correlationId) {
        final org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set(CORRELATION_HEADER, correlationId);
        this.copy(upstreamHeaders, headers, "Content-Type");
        this.copy(upstreamHeaders, headers, "ETag");
        this.copy(upstreamHeaders, headers, "X-Graph-Revision");
        this.copy(upstreamHeaders, headers, "Cache-Control");
        this.copy(upstreamHeaders, headers, "Server-Timing");
        return headers;
    }

    private void copy(final HttpHeaders upstreamHeaders,
                      final org.springframework.http.HttpHeaders downstreamHeaders,
                      final String header) {
        final List<String> values = upstreamHeaders.map()
                .entrySet()
                .stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(header))
                .flatMap(entry -> entry.getValue().stream())
                .toList();
        if (!values.isEmpty()) {
            downstreamHeaders.put(header, values);
        }
    }

    private String correlationId(final org.springframework.http.HttpHeaders incomingHeaders) {
        final String incoming = incomingHeaders == null ? null : incomingHeaders.getFirst(CORRELATION_HEADER);
        if (incoming != null && incoming.matches("[A-Za-z0-9._:-]{1,128}")) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    private Throwable unwrap(final Throwable throwable) {
        if ((throwable instanceof CompletionException || throwable instanceof java.util.concurrent.ExecutionException)
                && throwable.getCause() != null) {
            return this.unwrap(throwable.getCause());
        }
        return throwable;
    }

    private String serviceLabel(final InfrastructureProxyRoute route) {
        return switch (route.service()) {
            case KNOWLEDGE -> "Knowledge";
            case JARVIS -> "Jarvis";
        };
    }

    public Map<String, Object> productionScanFacts() {
        final Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("transport", this.getClass().getName());
        facts.put("httpClient", this.httpClient.getClass().getName());
        facts.put("requestBodyLimitBytes", this.properties.getProxy().getMaxRequestBodyBytes());
        facts.put("responseBodyLimitBytes", this.properties.getProxy().getMaxResponseBodyBytes());
        facts.put("rawJsonForwarding", true);
        return facts;
    }
}
