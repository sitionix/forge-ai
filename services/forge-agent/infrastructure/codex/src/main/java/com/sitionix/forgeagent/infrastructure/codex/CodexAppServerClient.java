package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.application.runtime.AgentExecutionException;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
final class CodexAppServerClient implements CodexRpcClient, CodexTurnClient {

    private static final Pattern USER_AGENT_VERSION = Pattern.compile("^[^/]+/([^\\s]+).*");

    private final ObjectMapper objectMapper;
    private final CodexAppServerProcessStarter processStarter;
    private final CodexAppServerProperties properties;
    private final CodexTurnStateTracker turnStateTracker = new CodexTurnStateTracker();
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
        } catch (final CodexTransportException e) {
            this.invalidate(current);
            throw e;
        }
    }

    @Override
    public CodexTurnResult execute(final CodexTurnRequest request) {
        CodexJsonRpcTransport current = this.ensureInitialized();
        CodexTurnStateTracker.ActiveTurn active = null;
        try {
            final String threadId = this.turnStateTracker.requireThreadId(current.request(
                    "thread/start",
                    this.threadStartParams(request.modelId()),
                    this.properties.getRequestTimeout()
            ));
            this.turnStateTracker.beginPreRegistration(threadId);
            final String turnId;
            try {
                turnId = this.turnStateTracker.requireTurnId(current.request(
                        "turn/start",
                        this.turnStartParams(threadId, request),
                        this.properties.getRequestTimeout()
                ));
            } catch (final RuntimeException e) {
                this.turnStateTracker.endPreRegistration(threadId);
                throw e;
            }
            active = this.turnStateTracker.register(threadId, turnId);
            if (!current.healthy()) {
                active.fail(new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed."));
            }
            final String output = active.future().get(this.properties.getTurnTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return new CodexTurnResult(threadId, turnId, output);
        } catch (final TimeoutException e) {
            if (active != null) {
                this.bestEffortInterrupt(current, active);
                this.turnStateTracker.remove(active);
            }
            throw new AgentExecutionException("CODEX_EXECUTION_TIMEOUT", "Codex execution timed out.", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            if (active != null) {
                this.turnStateTracker.remove(active);
            }
            throw new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed.", e);
        } catch (final ExecutionException e) {
            if (active != null) {
                this.turnStateTracker.remove(active);
            }
            final Throwable cause = e.getCause();
            if (cause instanceof CodexTurnStateTracker.PolicyViolationException policyViolationException) {
                if (active != null) {
                    this.bestEffortInterrupt(current, active);
                }
                final Throwable policyCause = policyViolationException.getCause();
                if (policyCause instanceof AgentExecutionException agentExecutionException) {
                    throw agentExecutionException;
                }
                throw new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed.", policyCause);
            }
            if (cause instanceof AgentExecutionException agentExecutionException) {
                throw agentExecutionException;
            }
            throw new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed.", cause);
        } catch (final CodexTransportException e) {
            this.invalidate(current);
            throw new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed.", e);
        } catch (final CodexRemoteException e) {
            throw new AgentExecutionException("CODEX_EXECUTION_FAILED", "Codex execution failed.", e);
        } finally {
            if (active != null) {
                this.turnStateTracker.remove(active);
            }
        }
    }

    int activeTurnCountForTesting() {
        return this.turnStateTracker.activeTurnCount();
    }

    int bufferedNotificationCountForTesting() {
        return this.turnStateTracker.bufferedNotificationCount();
    }

    private synchronized CodexJsonRpcTransport ensureInitialized() {
        if (this.transport != null && this.transport.healthy()) {
            return this.transport;
        }
        this.closeCurrent();
        final StartedCodexAppServer started = this.processStarter.start();
        final CodexJsonRpcTransport next = new CodexJsonRpcTransport(
                this.objectMapper,
                started,
                this.properties,
                this.turnStateTracker::handleServerRequest,
                new CodexTransportEventHandler() {
                    @Override
                    public void handleNotification(final String method, final JsonNode params) {
                        CodexAppServerClient.this.turnStateTracker.handleNotification(method, params);
                    }

                    @Override
                    public void transportFailed(final RuntimeException exception) {
                        CodexAppServerClient.this.turnStateTracker.failAll(exception);
                    }
                }
        );
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

    private ObjectNode threadStartParams(final String modelId) {
        final ObjectNode params = this.objectMapper.createObjectNode();
        params.put("model", modelId);
        params.put("approvalPolicy", "never");
        params.put("sandbox", "read-only");
        params.put("cwd", this.effectiveRuntimeCwd());
        params.put("ephemeral", true);
        return params;
    }

    private ObjectNode turnStartParams(final String threadId, final CodexTurnRequest request) {
        final ObjectNode params = this.objectMapper.createObjectNode();
        params.put("threadId", threadId);
        final ObjectNode input = this.objectMapper.createObjectNode();
        input.put("type", "text");
        input.put("text", request.prompt() == null ? "" : request.prompt());
        params.putArray("input").add(input);
        params.put("model", request.modelId());
        if (request.effortId() != null) {
            params.put("effort", request.effortId());
        }
        params.set("outputSchema", request.outputSchema().deepCopy());
        return params;
    }

    private String effectiveRuntimeCwd() {
        final String configured = this.properties.getRuntimeCwd();
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured.trim()).toAbsolutePath().normalize().toString();
        }
        final String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isBlank()) {
            return Paths.get(userDir).toAbsolutePath().normalize().toString();
        }
        return Paths.get("").toAbsolutePath().normalize().toString();
    }

    private void bestEffortInterrupt(final CodexJsonRpcTransport transport, final CodexTurnStateTracker.ActiveTurn active) {
        final ObjectNode params = this.objectMapper.createObjectNode();
        params.put("threadId", active.threadId());
        params.put("turnId", active.turnId());
        try {
            transport.request("turn/interrupt", params, this.properties.getRequestTimeout());
        } catch (final RuntimeException exception) {
            log.debug("Codex turn interrupt failed threadId={} turnId={} exceptionClass={}",
                    active.threadId(),
                    active.turnId(),
                    exception.getClass().getName());
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
        final Matcher matcher = USER_AGENT_VERSION.matcher(value);
        final String version = matcher.matches() ? matcher.group(1).trim() : "";
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
