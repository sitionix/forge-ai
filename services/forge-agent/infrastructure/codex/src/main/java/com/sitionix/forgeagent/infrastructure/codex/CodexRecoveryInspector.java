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
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
final class CodexRecoveryInspector implements AgentExecutionRecoveryInspector {

    private static final String PROVIDER_ID = "codex";

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
        StartedCodexAppServer started = null;
        CodexJsonRpcTransport transport = null;
        ProviderTurnRecoveryResult result;
        try {
            started = this.processStarter.start(inspection.executionWorkspace().cwd());
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
        } catch (final RuntimeException exception) {
            result = ProviderTurnRecoveryResult.unknown("Codex recovery process failed: "
                    + exception.getClass().getSimpleName());
        }
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
