package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
final class CodexAppServerClient implements CodexRpcClient {

    private final ObjectMapper objectMapper;
    private final CodexAppServerProcessStarter processStarter;
    private final CodexAppServerProperties properties;
    private CodexJsonRpcTransport transport;

    CodexAppServerClient(final ObjectMapper objectMapper,
                         final CodexAppServerProcessStarter processStarter,
                         final CodexAppServerProperties properties) {
        this.objectMapper = objectMapper;
        this.processStarter = processStarter;
        this.properties = properties;
    }

    @Override
    public synchronized String version() {
        return this.ensureInitialized().codexVersion();
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
        try {
            this.initialize(next);
            this.transport = next;
            return next;
        } catch (final RuntimeException e) {
            next.close();
            throw e;
        }
    }

    private void initialize(final CodexJsonRpcTransport transport) {
        final ObjectNode params = this.objectMapper.createObjectNode();
        final ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", this.properties.getClientName());
        clientInfo.put("title", this.properties.getClientTitle());
        clientInfo.put("version", this.properties.getClientVersion());
        final ObjectNode capabilities = params.putObject("capabilities");
        capabilities.put("experimentalApi", this.properties.isExperimentalApi());
        capabilities.put("requestAttestation", this.properties.isRequestAttestation());
        transport.request("initialize", params, this.properties.getRequestTimeout());
        transport.notify("initialized", this.objectMapper.createObjectNode());
    }

    private synchronized void invalidate(final CodexJsonRpcTransport current) {
        if (this.transport == current) {
            this.closeCurrent();
        }
    }

    private void closeCurrent() {
        if (this.transport != null) {
            this.transport.close();
            this.transport = null;
        }
    }

    @Override
    public synchronized void close() {
        this.closeCurrent();
    }
}
