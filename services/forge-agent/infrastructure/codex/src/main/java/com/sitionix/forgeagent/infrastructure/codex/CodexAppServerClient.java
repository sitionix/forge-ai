package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
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
final class CodexAppServerClient implements CodexClient {

    private static final Pattern USER_AGENT_VERSION = Pattern.compile("^[^/]+/([^\\s]+).*");
    private static final String SUPPORTED_DURABLE_VERSION = "0.153.2";

    private final ObjectMapper objectMapper;
    private final CodexAppServerProcessStarter processStarter;
    private final CodexAppServerProperties properties;
    private final CodexRuntimeWorkspace runtimeWorkspace;
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
    public String execute(final CodexTurnRequest request) {
        return this.executeInternal(request, null, null);
    }

    @Override
    public String executeDurable(final CodexTurnRequest request, final String existingThreadId,
                                 final CodexExecutionIdentityCallbacks callbacks) {
        return this.executeDurable(request, existingThreadId, null, callbacks);
    }

    @Override
    public String executeDurable(final CodexTurnRequest request, final String existingThreadId,
                                 final String expectedProviderVersion,
                                 final CodexExecutionIdentityCallbacks callbacks) {
        if (callbacks == null) throw new IllegalArgumentException("identity callbacks are required");
        return this.executeInternal(request, existingThreadId, expectedProviderVersion, callbacks, true);
    }

    @Override public String executeTrackedFresh(final CodexTurnRequest request, final CodexExecutionIdentityCallbacks callbacks) {
        return this.executeInternal(request, null, null, callbacks, false);
    }

    private String executeInternal(final CodexTurnRequest request, final String existingThreadId,
                                   final CodexExecutionIdentityCallbacks callbacks) {
        return this.executeInternal(request,existingThreadId,null,callbacks,false);
    }

    private String executeInternal(final CodexTurnRequest request, final String existingThreadId,
                                   final String expectedProviderVersion,
                                   final CodexExecutionIdentityCallbacks callbacks, final boolean durable) {
        final CodexTurnStateTracker turnStateTracker = new CodexTurnStateTracker();
        final CodexJsonRpcTransport transport = this.startWorkspaceTransport(
                request.executionWorkspace().cwd(), turnStateTracker);
        CodexExecution execution = null;
        try {
            final String providerVersion = this.initialize(transport);
            if (durable) {
                this.validateDurableVersion(providerVersion, expectedProviderVersion);
            }
            execution = this.startExecution(transport, turnStateTracker, request, existingThreadId, callbacks, providerVersion, durable);
            return this.awaitExecution(execution);
        } finally {
            if (execution != null) {
                this.releaseExecution(execution);
            }
            transport.close();
        }
    }

    private void validateDurableVersion(final String providerVersion, final String expectedProviderVersion) {
        if (!SUPPORTED_DURABLE_VERSION.equals(providerVersion)) {
            throw new CodexTransportException(
                    "Codex durable context requires audited CLI version " + SUPPORTED_DURABLE_VERSION
                            + "; found " + providerVersion
            );
        }
        if (expectedProviderVersion != null && !expectedProviderVersion.isBlank()
                && !expectedProviderVersion.equals(providerVersion)) {
            throw new CodexTransportException(
                    "Codex durable context version changed from " + expectedProviderVersion
                            + " to " + providerVersion
            );
        }
    }

    private synchronized CodexJsonRpcTransport ensureInitialized() {
        if (this.transport != null && this.transport.healthy()) {
            return this.transport;
        }
        this.closeCurrent();
        final CodexJsonRpcTransport next = this.startNeutralTransport(
                this.runtimeWorkspace.routingWorkspace().cwd());
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

    private CodexJsonRpcTransport startWorkspaceTransport(final Path workingDirectory,
                                                           final CodexTurnStateTracker turnStateTracker) {
        final StartedCodexAppServer started = this.processStarter.start(workingDirectory);
        return new CodexJsonRpcTransport(
                this.objectMapper,
                started,
                this.properties,
                turnStateTracker::handleServerRequest,
                new CodexTransportEventHandler() {
                    @Override
                    public void handleNotification(final String method, final JsonNode params) {
                        turnStateTracker.handleNotification(method, params);
                    }

                    @Override
                    public void transportFailed(final RuntimeException exception) {
                        turnStateTracker.failAll(exception);
                    }
                }
        );
    }

    private CodexJsonRpcTransport startNeutralTransport(final Path workingDirectory) {
        return new CodexJsonRpcTransport(
                this.objectMapper,
                this.processStarter.start(workingDirectory),
                this.properties
        );
    }

    private CodexExecution startExecution(final CodexJsonRpcTransport current,
                                          final CodexTurnStateTracker turnStateTracker,
                                          final CodexTurnRequest request,
                                          final String existingThreadId,
                                          final CodexExecutionIdentityCallbacks callbacks,
                                          final String providerVersion, final boolean durable) {
        CodexExecutionState state = null;
        try {
            final CodexSessionProtocol sessionProtocol = new CodexSessionProtocol(this.objectMapper);
            final String threadId = callbacks == null
                    ? this.startThread(current, turnStateTracker, request)
                    : existingThreadId == null && durable
                        ? sessionProtocol.startDurableThread(current, this.threadStartParams(request), this.properties.getRequestTimeout())
                        : existingThreadId != null
                            ? sessionProtocol.resumeThread(current, existingThreadId, this.properties.getRequestTimeout())
                            : this.startThread(current, turnStateTracker, request);
            if (callbacks != null && existingThreadId == null) callbacks.conversationStarted(threadId, providerVersion);
            state = turnStateTracker.register(threadId);
            final String turnId = this.startTurn(current, turnStateTracker, threadId, request);
            if (callbacks != null) callbacks.turnStarted(turnId);
            turnStateTracker.bindTurnId(state, turnId);
            this.verifyTransportStillHealthy(current, state);
            return new CodexExecution(current, turnStateTracker, state);
        } catch (final CodexTransportException e) {
            if (state != null) {
                turnStateTracker.remove(state);
            }
            this.invalidate(current);
            throw this.executionFailed(e);
        } catch (final CodexRemoteException e) {
            if (state != null) {
                turnStateTracker.remove(state);
            }
            throw this.executionFailed(e);
        } catch (final RuntimeException e) {
            if (state != null) {
                turnStateTracker.remove(state);
            }
            throw e;
        }
    }


    private String startThread(final CodexJsonRpcTransport transport,
                               final CodexTurnStateTracker turnStateTracker,
                               final CodexTurnRequest request) {
        return turnStateTracker.requireThreadId(transport.request(
                CodexProtocol.THREAD_START,
                this.threadStartParams(request),
                this.properties.getRequestTimeout()
        ));
    }

    private String startTurn(
            final CodexJsonRpcTransport transport,
            final CodexTurnStateTracker turnStateTracker,
            final String threadId,
            final CodexTurnRequest request
    ) {
        return turnStateTracker.requireTurnId(transport.request(
                CodexProtocol.TURN_START,
                this.turnStartParams(threadId, request),
                this.properties.getRequestTimeout()
        ));
    }

    private void verifyTransportStillHealthy(final CodexJsonRpcTransport transport, final CodexExecutionState state) {
        if (!transport.healthy()) {
            state.fail(new CodexTransportException("Codex execution failed."));
        }
    }

    private String awaitExecution(final CodexExecution execution) {
        try {
            return execution.state().result().get(this.properties.getTurnTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (final TimeoutException e) {
            this.interrupt(execution);
            throw new CodexTransportException("Codex execution timed out.", e);
        } catch (final InterruptedException e) {
            this.interrupt(execution);
            Thread.currentThread().interrupt();
            throw new CodexTransportException("Codex execution failed.", e);
        } catch (final ExecutionException e) {
            if (execution.state().providerInterruptRequired()) {
                this.interrupt(execution);
            }
            throw this.normalizeExecutionFailure(e.getCause());
        }
    }

    private RuntimeException normalizeExecutionFailure(final Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new CodexTransportException("Codex execution failed.", cause);
    }

    private CodexTransportException executionFailed(final RuntimeException cause) {
        final String message = cause.getMessage();
        return new CodexTransportException(message == null || message.isBlank() ? "Codex execution failed." : message, cause);
    }

    private void interrupt(final CodexExecution execution) {
        final ObjectNode params = this.objectMapper.createObjectNode();
        params.put("threadId", execution.threadId());
        params.put("turnId", execution.turnId());
        final boolean interrupted = Thread.interrupted();
        try {
            execution.transport().request(CodexProtocol.TURN_INTERRUPT, params, this.properties.getRequestTimeout());
        } catch (final RuntimeException exception) {
            log.debug("Codex turn interrupt failed threadId={} turnId={} exceptionClass={}",
                    execution.threadId(),
                    execution.turnId(),
                    exception.getClass().getName());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void releaseExecution(final CodexExecution execution) {
        execution.turnStateTracker().remove(execution.state());
    }

    private ObjectNode threadStartParams(final CodexTurnRequest request) {
        final ObjectNode params = this.objectMapper.createObjectNode();
        params.put("model", request.modelId());
        params.put("developerInstructions", request.developerInstructions());
        params.put("approvalPolicy", CodexProtocol.APPROVAL_POLICY_NEVER);
        params.put("sandbox", CodexProtocol.SANDBOX_WORKSPACE_WRITE);
        params.put("cwd", request.executionWorkspace().cwd().toString());
        params.set("runtimeWorkspaceRoots", this.runtimeWorkspaceRoots(request));
        params.put("ephemeral", true);
        params.set("config", this.codexConfig());
        return params;
    }

    private ObjectNode turnStartParams(final String threadId, final CodexTurnRequest request) {
        final ObjectNode params = this.objectMapper.createObjectNode();
        params.put("threadId", threadId);
        final ObjectNode input = this.objectMapper.createObjectNode();
        input.put("type", "text");
        input.put("text", request.userInput());
        input.putArray("text_elements");
        params.putArray("input").add(input);
        params.put("model", request.modelId());
        if (request.effortId() != null) {
            params.put("effort", request.effortId());
        }
        params.set("outputSchema", request.outputSchema().deepCopy());
        return params;
    }

    private ObjectNode codexConfig() {
        final ObjectNode config = this.objectMapper.createObjectNode();
        config.put("web_search", "disabled");
        final ObjectNode features = config.putObject("features");
        features.put("shell_tool", true);
        final ObjectNode agents = config.putObject("agents");
        agents.put("enabled", false);
        return config;
    }

    private ArrayNode runtimeWorkspaceRoots(final CodexTurnRequest request) {
        final ArrayNode roots = this.objectMapper.createArrayNode();
        for (final Path root : request.executionWorkspace().workspaceRoots()) {
            roots.add(root.toString());
        }
        return roots;
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
        final JsonNode response = transport.request(CodexProtocol.INITIALIZE, params, this.properties.getRequestTimeout());
        final String version = this.extractVersion(response);
        transport.notify(CodexProtocol.INITIALIZED, this.objectMapper.createObjectNode());
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

    private record CodexExecution(CodexJsonRpcTransport transport,
                                  CodexTurnStateTracker turnStateTracker,
                                  CodexExecutionState state) {

        private String threadId() {
            return this.state.threadId();
        }

        private String turnId() {
            return this.state.turnId();
        }
    }
}
