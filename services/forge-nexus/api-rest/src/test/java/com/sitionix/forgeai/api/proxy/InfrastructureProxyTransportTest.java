package com.sitionix.forgeai.api.proxy;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpHeaders;

class InfrastructureProxyTransportTest {

    @Test
    void explanationRouteRequestTimeoutUsesConfiguredKnowledgeDeadlinePlusTransportGrace() {
        final HttpClient httpClient = mock(HttpClient.class);
        final CompletableFuture<HttpResponse<InputStream>> upstream = new CompletableFuture<>();
        when(httpClient.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(upstream);

        final InfrastructureProxyProperties properties = new InfrastructureProxyProperties();
        properties.getProxy().setKnowledgeExplanationRequestDeadline(Duration.ofSeconds(42));
        properties.getProxy().setKnowledgeExplanationTransportGrace(Duration.ofSeconds(7));
        final ObjectMapper objectMapper = new ObjectMapper();
        final InfrastructureProxyTransport transport = new InfrastructureProxyTransport(
                httpClient,
                new InfrastructureProxyRouteRegistry(properties),
                properties,
                new InfrastructureProxyResponseMapper(objectMapper),
                objectMapper
        );
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getQueryString()).thenReturn(null);

        transport.forward(
                "knowledge.query.flow-explanations",
                Map.of(),
                "{\"queryText\":\"A.start\"}".getBytes(UTF_8),
                new HttpHeaders(),
                request
        );

        final ArgumentCaptor<HttpRequest> upstreamRequest = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).sendAsync(upstreamRequest.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
        assertThat(upstreamRequest.getValue().timeout()).contains(Duration.ofSeconds(49));
    }
}
