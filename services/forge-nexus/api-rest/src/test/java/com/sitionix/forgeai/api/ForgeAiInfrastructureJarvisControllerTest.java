package com.sitionix.forgeai.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.api.proxy.InfrastructureProxyTransport;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class ForgeAiInfrastructureJarvisControllerTest {

    private final InfrastructureProxyTransport transport = mock(InfrastructureProxyTransport.class);
    private final ForgeAiInfrastructureJarvisController controller = new ForgeAiInfrastructureJarvisController(this.transport);
    private final HttpHeaders headers = new HttpHeaders();
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void statusDelegatesToGenericProxyRoute() {
        this.stub();

        this.controller.status(this.headers, this.request);

        verify(this.transport).forward("jarvis.status", Map.of(), null, this.headers, this.request);
    }

    @Test
    void actionsDelegatesToGenericProxyRoute() {
        this.stub();

        this.controller.actions(this.headers, this.request);

        verify(this.transport).forward("jarvis.actions", Map.of(), null, this.headers, this.request);
    }

    @Test
    void commandDelegatesRawBodyToGenericProxyRoute() {
        this.stub();
        final byte[] body = "{\"text\":\"status\"}".getBytes(StandardCharsets.UTF_8);

        this.controller.command(body, this.headers, this.request);

        verify(this.transport).forward("jarvis.command", Map.of(), body, this.headers, this.request);
    }

    @Test
    void chatDelegatesRawBodyToGenericProxyRoute() {
        this.stub();
        final byte[] body = "{\"message\":\"hello\"}".getBytes(StandardCharsets.UTF_8);

        this.controller.chat(body, this.headers, this.request);

        verify(this.transport).forward("jarvis.chat", Map.of(), body, this.headers, this.request);
    }

    private void stub() {
        when(this.transport.forward(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ResponseEntity.ok(new byte[0])));
    }
}
