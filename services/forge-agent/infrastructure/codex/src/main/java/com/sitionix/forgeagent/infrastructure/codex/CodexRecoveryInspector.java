package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.application.runtime.AgentExecutionRecoveryInspection;
import com.sitionix.forgeagent.application.runtime.AgentExecutionRecoveryInspector;
import com.sitionix.forgeagent.application.runtime.ProviderTurnRecoveryResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
final class CodexRecoveryInspector implements AgentExecutionRecoveryInspector {

    private static final String PROVIDER_ID = "codex";
    private static final AtomicLong INSPECTION_IDS = new AtomicLong();

    private final ObjectMapper objectMapper;
    private final CodexAppServerProcessStarter processStarter;
    private final CodexAppServerProperties properties;
    private final CodexRecoveryProtocol recoveryProtocol;
    private final Clock clock;

    CodexRecoveryInspector(final ObjectMapper objectMapper, final CodexAppServerProcessStarter processStarter,
                           final CodexAppServerProperties properties, final CodexRecoveryProtocol recoveryProtocol,
                           final Clock clock) {
        this.objectMapper = objectMapper;
        this.processStarter = processStarter;
        this.properties = properties;
        this.recoveryProtocol = recoveryProtocol;
        this.clock = clock;
    }

    @Override
    public boolean supports(final String providerId, final String providerVersion) {
        return PROVIDER_ID.equals(providerId)
                && CodexAppServerClient.SUPPORTED_RECOVERY_VERSION.equals(providerVersion);
    }

    @Override
    public ProviderTurnRecoveryResult inspect(final AgentExecutionRecoveryInspection inspection) {
        if (inspection == null || !this.supports(inspection.providerId(), inspection.providerVersion())) {
            return ProviderTurnRecoveryResult.unknown("Codex recovery provider or version is unsupported");
        }
        if (isBlank(inspection.providerConversationId()) || isBlank(inspection.providerTurnId())
                || inspection.executionWorkspace() == null || inspection.deadline() == null) {
            return ProviderTurnRecoveryResult.unknown("Codex recovery identity or workspace is invalid");
        }
        final Instant requestDeadline = inspection.deadline().minus(this.cleanupReserve());
        if (!requestDeadline.isAfter(this.clock.instant())) {
            return ProviderTurnRecoveryResult.unknown("Codex recovery deadline does not permit process inspection");
        }
        final Instant forceDeadline = inspection.deadline().minus(this.properties.getForceKillTimeout());
        final CodexRecoveryLifecycle lifecycle = new CodexRecoveryLifecycle();
        final CompletableFuture<Void> providerPhaseFinished = new CompletableFuture<>();
        final CompletableFuture<ProviderTurnRecoveryResult> result = new CompletableFuture<>();
        final CompletableFuture<Void> workerExited = new CompletableFuture<>();
        final Thread worker = Thread.ofVirtual()
                .name("forge-agent-codex-recovery-" + INSPECTION_IDS.incrementAndGet())
                .start(() -> {
                    try {
                        result.complete(this.inspectOwned(inspection, requestDeadline, lifecycle, providerPhaseFinished));
                    } catch (final RuntimeException exception) {
                        result.complete(ProviderTurnRecoveryResult.unknown("Codex recovery lifecycle failed: "
                                + exception.getClass().getSimpleName()));
                    } finally {
                        providerPhaseFinished.complete(null);
                        workerExited.complete(null);
                    }
                });
        try {
            this.await(providerPhaseFinished, requestDeadline);
        } catch (final TimeoutException exception) {
            this.abortAndAwait(lifecycle, worker, workerExited, inspection.deadline());
            return ProviderTurnRecoveryResult.unknown("Codex recovery provider phase exceeded its deadline");
        } catch (final InterruptedException exception) {
            lifecycle.abort();
            worker.interrupt();
            Thread.currentThread().interrupt();
            return ProviderTurnRecoveryResult.unknown("Codex recovery inspection was interrupted");
        } catch (final ExecutionException exception) {
            this.abortAndAwait(lifecycle, worker, workerExited, inspection.deadline());
            return ProviderTurnRecoveryResult.unknown("Codex recovery provider phase failed");
        }
        try {
            return this.await(result, forceDeadline);
        } catch (final TimeoutException exception) {
            this.abortAndAwait(lifecycle, worker, workerExited, inspection.deadline());
            return ProviderTurnRecoveryResult.unknown("Codex recovery cleanup exceeded its graceful deadline");
        } catch (final InterruptedException exception) {
            lifecycle.abort();
            worker.interrupt();
            Thread.currentThread().interrupt();
            return ProviderTurnRecoveryResult.unknown("Codex recovery cleanup was interrupted");
        } catch (final ExecutionException exception) {
            this.abortAndAwait(lifecycle, worker, workerExited, inspection.deadline());
            return ProviderTurnRecoveryResult.unknown("Codex recovery cleanup failed");
        }
    }

    private ProviderTurnRecoveryResult inspectOwned(final AgentExecutionRecoveryInspection inspection,
                                                    final Instant requestDeadline,
                                                    final CodexRecoveryLifecycle lifecycle,
                                                    final CompletableFuture<Void> providerPhaseFinished) {
        StartedCodexAppServer started = null;
        CodexJsonRpcTransport transport = null;
        ProviderTurnRecoveryResult result;
        try {
            started = this.processStarter.start(inspection.executionWorkspace().cwd());
            if (!lifecycle.register(started.process()) || lifecycle.aborted()) {
                result = ProviderTurnRecoveryResult.unknown("Codex recovery process started after cancellation");
            } else {
                transport = new CodexJsonRpcTransport(
                        this.objectMapper,
                        started,
                        this.properties,
                        this.clock,
                        inspection.deadline()
                );
                final String liveVersion = this.initialize(transport, requestDeadline);
                if (!CodexAppServerClient.SUPPORTED_RECOVERY_VERSION.equals(liveVersion)
                        || !Objects.equals(inspection.providerVersion(), liveVersion)) {
                    result = ProviderTurnRecoveryResult.unknown(
                            "Codex recovery live version did not match persisted version");
                } else {
                    result = this.recoveryProtocol.inspectTurn(
                            transport,
                            inspection.providerConversationId(),
                            inspection.providerTurnId(),
                            requestDeadline,
                            this.properties.getRequestTimeout()
                    );
                }
            }
        } catch (final RuntimeException exception) {
            result = ProviderTurnRecoveryResult.unknown("Codex recovery process failed: "
                    + exception.getClass().getSimpleName());
        }
        providerPhaseFinished.complete(null);
        try {
            if (transport != null) {
                transport.close();
            } else if (started != null) {
                this.closeUnownedProcess(started.process(), inspection.deadline());
            }
        } catch (final RuntimeException cleanupFailure) {
            return ProviderTurnRecoveryResult.unknown("Codex recovery process cleanup failed: "
                    + cleanupFailure.getClass().getSimpleName());
        }
        if (!inspection.deadline().isAfter(this.clock.instant())) {
            return ProviderTurnRecoveryResult.unknown("Codex recovery deadline was exhausted during cleanup");
        }
        return result;
    }

    private <T> T await(final CompletableFuture<T> future, final Instant deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        final Duration remaining = Duration.between(this.clock.instant(), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new TimeoutException("Codex recovery lifecycle deadline exhausted");
        }
        return future.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void abortAndAwait(final CodexRecoveryLifecycle lifecycle, final Thread worker,
                               final CompletableFuture<Void> workerExited, final Instant deadline) {
        lifecycle.abort();
        worker.interrupt();
        try {
            this.await(workerExited, deadline);
        } catch (final TimeoutException | ExecutionException ignored) {
            // The process abort is already issued; never wait beyond the authoritative inspection deadline.
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeUnownedProcess(final Process process, final Instant deadline) {
        if (!process.isAlive()) {
            return;
        }
        try {
            process.destroy();
            if (!process.waitFor(this.remainingTimeout(deadline, this.properties.getGracefulTerminateTimeout()).toMillis(),
                    TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(this.remainingTimeout(deadline, this.properties.getForceKillTimeout()).toMillis(),
                        TimeUnit.MILLISECONDS)) {
                    throw new CodexTransportException(
                            "Codex app-server process remained alive after construction failure");
                }
            }
            if (process.isAlive()) {
                throw new CodexTransportException(
                        "Codex app-server process cleanup incomplete after construction failure");
            }
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new CodexTransportException(
                    "Codex app-server cleanup interrupted after construction failure", exception);
        } catch (final RuntimeException exception) {
            process.destroyForcibly();
            throw exception;
        }
    }

    private String initialize(final CodexJsonRpcTransport transport, final Instant deadline) {
        final ObjectNode params = this.objectMapper.createObjectNode();
        final ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", this.properties.getClientName());
        clientInfo.put("title", this.properties.getClientTitle());
        clientInfo.put("version", this.properties.getClientVersion());
        final ObjectNode capabilities = params.putObject("capabilities");
        capabilities.put("experimentalApi", this.properties.isExperimentalApi());
        capabilities.put("requestAttestation", this.properties.isRequestAttestation());
        final JsonNode response = transport.request(
                CodexProtocol.INITIALIZE, params, this.remainingTimeout(deadline, this.properties.getRequestTimeout()));
        final String version = CodexAppServerClient.extractVersion(response);
        transport.notify(CodexProtocol.INITIALIZED, this.objectMapper.createObjectNode());
        return version;
    }

    private Duration cleanupReserve() {
        return this.properties.getGracefulTerminateTimeout().plus(this.properties.getForceKillTimeout());
    }

    private Duration remainingTimeout(final Instant deadline, final Duration maximum) {
        final Duration remaining = Duration.between(this.clock.instant(), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new CodexTransportException("Codex recovery deadline was exhausted");
        }
        return remaining.compareTo(maximum) < 0 ? remaining : maximum;
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
