package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
final class CodexAppServerClient implements CodexRpcClient {

    private final ObjectMapper objectMapper;
    private final CodexAppServerProcessStarter processStarter;
    private final CodexAppServerProperties properties;
    private CodexJsonRpcTransport transport;
    private String codexVersion;

    @Override
    public synchronized String version() {
        this.ensureInitialized();
        return this.codexVersion;
    }

    @Override
    public JsonNode request(final String method, final JsonNode params) {
        CodexJsonRpcTransport current = this.ensureInitialized();
        try {
            return current.request(method, params, this.properties.getRequestTimeout());
        } catch (final CodexTransportException | CodexRemoteException e) {
            this.invalidate(current);
            throw e;
        }
    }

    private synchronized CodexJsonRpcTransport ensureInitialized() {
        if (this.transport != null && this.transport.healthy()) {
            return this.transport;
        }
        this.closeCurrent();
        final StartedCodexAppServer started = this.processStarter.start();
        final CodexJsonRpcTransport next = new CodexJsonRpcTransport(this.objectMapper, started, this.properties);
        this.transport = next;
        this.codexVersion = null;
        try {
            this.codexVersion = this.initialize(next);
            return next;
        } catch (final RuntimeException e) {
            try {
                this.closeCurrent();
            } catch (final RuntimeException cleanupFailure) {
                if (cleanupFailure != e) {
                    cleanupFailure.addSuppressed(e);
                }
                throw cleanupFailure;
            }
            throw e;
        }
    }

    private String initialize(final CodexJsonRpcTransport transport) {
        final ObjectNode params = this.objectMapper.createObjectNode();
        final ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", this.properties.getClientName());
        clientInfo.put("title", this.properties.getClientTitle());
        clientInfo.put("version", this.properties.getClientVersion());
        final ObjectNode capabilities = params.putObject("capabilities");
        capabilities.put("experimentalApi", this.properties.isExperimentalApi());
        capabilities.put("requestAttestation", this.properties.isRequestAttestation());
        final JsonNode response = transport.request("initialize", params, this.properties.getRequestTimeout());
        final String version = this.extractVersion(response);
        transport.notify("initialized", this.objectMapper.createObjectNode());
        return version;
    }

    private String extractVersion(final JsonNode initializeResult) {
        if (initializeResult == null || !initializeResult.isObject()) {
            throw new CodexTransportException("Codex initialize response was not an object");
        }
        final JsonNode userAgent = initializeResult.path("userAgent");
        if (!userAgent.isTextual() || userAgent.asText().isBlank()) {
            throw new CodexTransportException("Codex initialize response did not include a valid userAgent");
        }
        final String value = userAgent.asText().trim();
        final int separator = value.indexOf('/');
        if (separator <= 0 || separator != value.lastIndexOf('/') || separator == value.length() - 1) {
            throw new CodexTransportException("Codex initialize response userAgent was malformed");
        }
        final String version = value.substring(separator + 1).trim();
        if (version.isBlank()) {
            throw new CodexTransportException("Codex initialize response userAgent was malformed");
        }
        return version;
    }

    private synchronized void invalidate(final CodexJsonRpcTransport current) {
        if (this.transport == current) {
            this.closeCurrent();
        }
    }

    private void closeCurrent() {
        if (this.transport != null) {
            final CodexJsonRpcTransport current = this.transport;
            current.close();
            if (!current.cleanupComplete()) {
                throw new CodexTransportException("Codex app-server process cleanup incomplete");
            }
            this.transport = null;
            this.codexVersion = null;
        }
    }

    @Override
    public synchronized void close() {
        this.closeCurrent();
    }
}
