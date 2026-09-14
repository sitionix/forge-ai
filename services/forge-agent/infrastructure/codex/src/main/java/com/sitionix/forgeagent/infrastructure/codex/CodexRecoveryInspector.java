package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.application.runtime.AgentExecutionRecoveryInspection;
import com.sitionix.forgeagent.application.runtime.AgentExecutionRecoveryInspector;
import com.sitionix.forgeagent.application.runtime.ProviderTurnRecoveryResult;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
final class CodexRecoveryInspector implements AgentExecutionRecoveryInspector {

    private static final String PROVIDER_ID = "codex";

    private final ObjectMapper objectMapper;
    private final CodexAppServerProcessStarter processStarter;
    private final CodexAppServerProperties properties;
    private final CodexRecoveryProtocol recoveryProtocol;

    CodexRecoveryInspector(final ObjectMapper objectMapper, final CodexAppServerProcessStarter processStarter,
                           final CodexAppServerProperties properties, final CodexRecoveryProtocol recoveryProtocol) {
        this.objectMapper = objectMapper;
        this.processStarter = processStarter;
        this.properties = properties;
        this.recoveryProtocol = recoveryProtocol;
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
                || inspection.executionWorkspace() == null) {
            return ProviderTurnRecoveryResult.unknown("Codex recovery identity or workspace is invalid");
        }
        CodexJsonRpcTransport transport = null;
        try {
            transport = new CodexJsonRpcTransport(
                    this.objectMapper,
                    this.processStarter.start(inspection.executionWorkspace().cwd()),
                    this.properties
            );
            final String liveVersion = this.initialize(transport);
            if (!CodexAppServerClient.SUPPORTED_RECOVERY_VERSION.equals(liveVersion)
                    || !Objects.equals(inspection.providerVersion(), liveVersion)) {
                return ProviderTurnRecoveryResult.unknown("Codex recovery live version did not match persisted version");
            }
            return this.recoveryProtocol.inspectTurn(
                    transport,
                    inspection.providerConversationId(),
                    inspection.providerTurnId(),
                    this.properties.getRequestTimeout()
            );
        } catch (final RuntimeException exception) {
            return ProviderTurnRecoveryResult.unknown("Codex recovery process failed: "
                    + exception.getClass().getSimpleName());
        } finally {
            if (transport != null) {
                try {
                    transport.close();
                } catch (final RuntimeException ignored) {
                    // Inspection is fail-closed and the transport already attempted process teardown.
                }
            }
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
        final JsonNode response = transport.request(
                CodexProtocol.INITIALIZE, params, this.properties.getRequestTimeout());
        final String version = CodexAppServerClient.extractVersion(response);
        transport.notify(CodexProtocol.INITIALIZED, this.objectMapper.createObjectNode());
        return version;
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
