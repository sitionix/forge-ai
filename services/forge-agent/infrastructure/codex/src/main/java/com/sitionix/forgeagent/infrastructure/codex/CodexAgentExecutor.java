package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitionix.forgeagent.application.runtime.AgentExecutor;
import com.sitionix.forgeagent.application.runtime.AgentExecutionResult;
import com.sitionix.forgeagent.application.runtime.NodeExecutionClaim;
import com.sitionix.forgeagent.application.mcp.McpExecutionSelectionService;
import com.sitionix.forgeagent.domain.model.McpExecutionPreparation;
import com.sitionix.forgeagent.domain.model.NodeInputContribution;
import com.sitionix.forgeagent.domain.model.NodeInputEnvelope;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.RunPort;
import com.sitionix.forgeagent.domain.port.AgentExecutionDispatchGuard;
import com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import com.sitionix.forgeagent.application.runtime.AgentSessionLeaseService;
import com.sitionix.forgeagent.application.runtime.AgentExecutionEventRecorder;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import org.springframework.stereotype.Component;

@Component
public final class CodexAgentExecutor implements AgentExecutor {

    private static final String PROVIDER_ID = "codex";
    private static final String PAYLOAD_SCHEMA_DEFINITION = "__forge_payload";
    private static final String PAYLOAD_SCHEMA_POINTER = "#/$defs/" + PAYLOAD_SCHEMA_DEFINITION;

    private final ObjectMapper objectMapper;
    private final CodexClient client;
    private final AgentSessionLeaseService sessionLeaseService;
    private final AgentExecutionEventRecorder eventRecorder;
    private final AgentExecutionDispatchGuard dispatchGuard;
    private final McpExecutionSelectionService mcpSelectionService;
    private final CodexAppServerProperties properties;
    private final ConcurrentHashMap<UUID, ExecutionCancellation> activeExecutions = new ConcurrentHashMap<>();

    @Autowired
    public CodexAgentExecutor(final ObjectMapper objectMapper, final CodexClient client,
                              final AgentSessionLeaseService sessionLeaseService,
                              final AgentExecutionEventRecorder eventRecorder,
                              final AgentExecutionDispatchGuard dispatchGuard,
                              @Nullable final McpExecutionSelectionService mcpSelectionService,
                              final CodexAppServerProperties properties) {
        this.objectMapper = objectMapper;
        this.client = client;
        this.sessionLeaseService = sessionLeaseService;
        this.eventRecorder = eventRecorder;
        this.dispatchGuard = dispatchGuard;
        this.mcpSelectionService = mcpSelectionService;
        this.properties = properties;
    }

    CodexAgentExecutor(final ObjectMapper objectMapper, final CodexClient client,
                       final AgentSessionLeaseService sessionLeaseService,
                       final AgentExecutionEventRecorder eventRecorder,
                       final AgentExecutionDispatchGuard dispatchGuard) {
        this(objectMapper, client, sessionLeaseService, eventRecorder, dispatchGuard,
                null, new CodexAppServerProperties());
    }

    CodexAgentExecutor(final ObjectMapper objectMapper, final CodexClient client,
                       final AgentSessionLeaseService leases, final AgentExecutionEventRecorder events) {
        this(objectMapper, client, leases, events, null);
    }

    CodexAgentExecutor(final ObjectMapper objectMapper, final CodexClient client) {
        this(objectMapper, client, null, null);
    }

    @Override
    public AgentExecutionResult execute(final NodeExecutionClaim claim) {
        if (!PROVIDER_ID.equals(claim.executionModel().providerId())) {
            throw new IllegalStateException("Agent provider is not supported.");
        }
        final McpExecutionPreparation prepared;
        try {
            prepared = this.mcpSelectionService == null ? null
                    : this.mcpSelectionService.prepare(claim, Instant.now().plus(this.properties.getTurnTimeout()));
        } catch (RuntimeException failure) {
            throw new InfrastructureExecutionException("MCP_EXECUTION_FAILED", "MCP execution preparation failed.");
        }
        try {
            final JsonNode userOutputSchema = this.parseOutputSchema(claim);
            final boolean selectionRequired = claim.availableOutputs().size() > 1;
            final JsonNode effectiveOutputSchema = selectionRequired
                    ? this.effectiveOutputSchema(userOutputSchema, claim.availableOutputs())
                    : userOutputSchema;
            final CodexTurnRequest request = new CodexTurnRequest(
                    this.userInput(claim),
                    WorkflowExecutionDeveloperInstructions.compose(claim.agentInstructions()),
                    claim.executionModel().modelId(),
                    claim.executionModel().effortId(),
                    effectiveOutputSchema,
                    claim.executionWorkspace(),
                    claim.agentSessionClaim() != null && claim.agentSessionClaim().contextMode()
                            == com.sitionix.forgeagent.domain.model.NodeContextMode.SHARED_SESSION_GROUP,
                    prepared == null ? null : prepared.selection()
            );
            final String outputText;
            if (claim.agentSessionClaim() == null) {
                outputText = prepared == null ? this.client.execute(request)
                        : this.client.execute(request, prepared.launchGrants());
            } else {
                final ExecutionCancellation cancellation = new ExecutionCancellation();
                this.activeExecutions.put(claim.nodeRunId(), cancellation);
                try {
                    final CodexExecutionIdentityCallbacks callbacks =
                            new CodexExecutionIdentityCallbacks() {
                            @Override public void executionStarted(final Runnable cancel) {
                                cancellation.register(cancel);
                            }
                            @Override public void conversationStarted(String threadId, String version) {
                                CodexAgentExecutor.this.persistConversationIdentity(claim, threadId, version);
                            }
                            @Override public void turnStarted(String turnId) {
                                CodexAgentExecutor.this.persistTurnIdentity(claim, turnId);
                                if (CodexAgentExecutor.this.eventRecorder != null) {
                                    CodexAgentExecutor.this.eventRecorder.activate(claim.agentSessionClaim());
                                }
                            }
                            @Override public void dispatchTurnStart(final Runnable writeRequest) {
                                if (CodexAgentExecutor.this.dispatchGuard == null) {
                                    throw new IllegalStateException("Tracked execution requires a dispatch guard.");
                                }
                                CodexAgentExecutor.this.dispatchGuard.dispatch(claim.agentSessionClaim(), writeRequest);
                            }
                            @Override public void executionEvent(
                                    com.sitionix.forgeagent.domain.model.AgentExecutionEventCandidate event) {
                                if (CodexAgentExecutor.this.eventRecorder != null) {
                                    CodexAgentExecutor.this.eventRecorder.record(claim.agentSessionClaim(), event);
                                }
                            }
                            @Override public void eventCaptureCompleted(
                                    com.sitionix.forgeagent.domain.model.AgentExecutionEventCandidate event) {
                                if (CodexAgentExecutor.this.eventRecorder != null) {
                                    CodexAgentExecutor.this.eventRecorder.complete(claim.agentSessionClaim(), event);
                                }
                            }
                            @Override public void eventCaptureDegraded(final RuntimeException failure) {
                                if (CodexAgentExecutor.this.eventRecorder != null) {
                                    CodexAgentExecutor.this.eventRecorder.degrade(
                                            claim.agentSessionClaim(), "normalize", failure);
                                }
                            }
                            };
                    outputText = this.executeTracked(request, claim, prepared, callbacks);
                } catch (ConflictException exception) {
                    throw exception;
                } catch (CodexExecutionException exception) {
                    if (exception.phase() == CodexExecutionFailurePhase.TURN_EXECUTION) throw exception;
                    final String code = switch (exception.phase()) {
                        case THREAD_START -> "AGENT_CONTEXT_START_FAILED";
                        case THREAD_RESUME -> "AGENT_CONTEXT_RESUME_FAILED";
                        case IDENTITY -> "AGENT_CONTEXT_IDENTITY_MISMATCH";
                        case TURN_EXECUTION -> throw exception;
                    };
                    final String message = exception.phase() == CodexExecutionFailurePhase.THREAD_RESUME
                            ? "Could not continue the existing context. No fresh context was started."
                            : exception.phase() == CodexExecutionFailurePhase.THREAD_START
                                ? "Could not start the agent context."
                                : "Provider execution identity did not match the Forge session.";
                    throw new ConflictException(code, message);
                } catch (RuntimeException exception) {
                    throw exception;
                } finally {
                    cancellation.executionFinished();
                    this.reconcileCancellation(claim.nodeRunId(), cancellation);
                }
            }
            return this.parseExecutionResult(outputText, claim.availableOutputs(), selectionRequired);
        } catch (RuntimeException failure) {
            if (prepared != null && !(failure instanceof ConflictException))
                throw new InfrastructureExecutionException("MCP_EXECUTION_FAILED", "MCP execution failed.");
            throw failure;
        } finally {
            if (prepared != null && claim.agentSessionClaim() != null) {
                try { this.mcpSelectionService.revoke(claim.agentSessionClaim()); }
                catch (RuntimeException failure) {
                    throw new InfrastructureExecutionException("MCP_EXECUTION_FAILED", "MCP grant cleanup failed.");
                }
            }
        }
    }

    private String executeTracked(final CodexTurnRequest request, final NodeExecutionClaim claim,
                                  final McpExecutionPreparation prepared,
                                  final CodexExecutionIdentityCallbacks callbacks) {
        if (claim.agentSessionClaim().contextMode()
                == com.sitionix.forgeagent.domain.model.NodeContextMode.FRESH_EACH_NODE_RUN) {
            return prepared == null ? this.client.executeTrackedFresh(request, callbacks)
                    : this.client.executeTrackedFresh(request, prepared.launchGrants(), callbacks);
        }
        String threadId = claim.agentSessionClaim().providerConversationId();
        String version = claim.agentSessionClaim().providerVersion();
        return prepared == null ? this.client.executeDurable(request, threadId, version, callbacks)
                : this.client.executeDurable(request, threadId, version, prepared.launchGrants(), callbacks);
    }

    @Override
    public void cancel(final NodeExecutionClaim claim) {
        this.secureCancellation(claim.nodeRunId()).ifPresent(Runnable::run);
    }

    @Override
    public Optional<Runnable> secureCancellation(final UUID nodeRunId) {
        return Optional.ofNullable(this.activeExecutions.get(nodeRunId))
                .map(cancellation -> () -> {
                    try {
                        cancellation.cancel();
                    } finally {
                        this.reconcileCancellation(nodeRunId, cancellation);
                    }
                });
    }

    private void reconcileCancellation(final UUID nodeRunId, final ExecutionCancellation cancellation) {
        this.activeExecutions.compute(nodeRunId, (id, current) -> {
            if (cancellation.isComplete()) {
                return current == cancellation ? null : current;
            }
            if (cancellation.hasUnresolvedCancellation()) {
                return current == null ? cancellation : current;
            }
            return current == cancellation ? null : current;
        });
    }

    private static final class ExecutionCancellation {
        private boolean requested;
        private boolean complete;
        private boolean executionFinished;
        private boolean inProgress;
        private Runnable action;

        synchronized void register(final Runnable cancellation) {
            this.action = cancellation;
            this.notifyAll();
        }

        void cancel() {
            final Runnable cancellation;
            boolean interrupted = false;
            synchronized (this) {
                this.requested = true;
                while (this.action == null && !this.executionFinished) {
                    try {
                        this.wait();
                    } catch (final InterruptedException exception) {
                        interrupted = true;
                    }
                }
                while (this.inProgress && !this.complete) {
                    try {
                        this.wait();
                    } catch (final InterruptedException exception) {
                        interrupted = true;
                    }
                }
                if (this.complete) {
                    if (interrupted) Thread.currentThread().interrupt();
                    return;
                }
                if (this.action == null) {
                    if (interrupted) Thread.currentThread().interrupt();
                    throw new IllegalStateException("Provider execution ended before cancellation became available.");
                }
                this.inProgress = true;
                cancellation = this.action;
            }

            try {
                cancellation.run();
                synchronized (this) {
                    this.complete = true;
                }
            } finally {
                synchronized (this) {
                    this.inProgress = false;
                    this.notifyAll();
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        synchronized void executionFinished() {
            this.executionFinished = true;
            this.notifyAll();
        }

        synchronized boolean isComplete() {
            return this.complete;
        }

        synchronized boolean hasUnresolvedCancellation() {
            return this.requested && this.action != null && !this.complete;
        }
    }

    private void persistConversationIdentity(final NodeExecutionClaim claim, final String threadId,
                                             final String providerVersion) {
        try {
            this.sessionLeaseService.persistConversation(claim.agentSessionClaim(), threadId, providerVersion);
        } catch (final ConflictException exception) {
            throw exception;
        } catch (final RuntimeException exception) {
            throw new ConflictException(
                    "AGENT_CONTEXT_PERSISTENCE_FAILED",
                    "Could not persist the provider conversation identity."
            );
        }
    }

    private void persistTurnIdentity(final NodeExecutionClaim claim, final String turnId) {
        try {
            this.sessionLeaseService.persistTurn(claim.agentSessionClaim(), turnId);
        } catch (final ConflictException exception) {
            throw exception;
        } catch (final RuntimeException exception) {
            throw new ConflictException(
                    "AGENT_CONTEXT_PERSISTENCE_FAILED",
                    "Could not persist the provider turn identity."
            );
        }
    }

    private String userInput(final NodeExecutionClaim claim) {
        try {
            final ObjectNode input = this.objectMapper.createObjectNode();
            final NodeInputEnvelope envelope = claim.inputEnvelope();
            if (envelope.originalTask() != null && !envelope.originalTask().isBlank()) {
                input.put("task", envelope.originalTask());
            }
            if (envelope.entryInputPort() != null) {
                final ObjectNode entryInput = input.putObject("entryInput");
                entryInput.put("id", envelope.entryInputPort().sourcePortId().toString());
                entryInput.put("name", envelope.entryInputPort().name());
                entryInput.put("description", envelope.entryInputPort().description());
            }
            final ArrayNode contributions = input.putArray("contributions");
            for (final NodeInputContribution contribution : envelope.contributions()) {
                final ObjectNode contributionNode = this.objectMapper.createObjectNode();
                contributionNode.put("sourceNodeRunId", contribution.sourceNodeRunId().toString());
                contributionNode.put("sourceConnectionId", contribution.sourceConnectionId().toString());
                if (contribution.sourceRepositoryId() == null) {
                    contributionNode.putNull("sourceRepositoryId");
                } else {
                    contributionNode.put("sourceRepositoryId", contribution.sourceRepositoryId().toString());
                }
                contributionNode.set("payload", this.parsePayload(contribution.payload()));
                contributions.add(contributionNode);
            }
            if (claim.availableOutputs().size() > 1) {
                final ArrayNode availableOutputs = input.putArray("availableOutputs");
                for (final RunPort outputPort : claim.availableOutputs()) {
                    final ObjectNode outputNode = this.objectMapper.createObjectNode();
                    outputNode.put("id", outputPort.sourcePortId().toString());
                    outputNode.put("name", outputPort.name());
                    outputNode.put("description", outputPort.description());
                    availableOutputs.add(outputNode);
                }
            }
            return this.objectMapper.writeValueAsString(input);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Codex execution failed.", e);
        }
    }

    private JsonNode parsePayload(final NodeRunOutput output) {
        try {
            return this.objectMapper.readTree(output.jsonValue());
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Codex execution failed.", e);
        }
    }

    private JsonNode parseOutputSchema(final NodeExecutionClaim claim) {
        if (claim.outputSchema() == null || claim.outputSchema().jsonObject() == null) {
            throw new IllegalStateException("Agent output schema is invalid.");
        }
        try {
            final JsonNode schema = this.objectMapper.readTree(claim.outputSchema().jsonObject());
            if (schema == null || !schema.isObject()) {
                throw new IllegalStateException("Agent output schema is invalid.");
            }
            return schema;
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Agent output schema is invalid.", e);
        }
    }

    private ObjectNode effectiveOutputSchema(final JsonNode userOutputSchema,
                                             final java.util.List<RunPort> outputs) {
        final ObjectNode schema = this.objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        final ObjectNode definitions = schema.putObject("$defs");
        final ObjectNode payloadSchema = userOutputSchema.deepCopy();
        this.rewriteLocalReferences(payloadSchema, true);
        definitions.set(PAYLOAD_SCHEMA_DEFINITION, payloadSchema);
        final ObjectNode properties = schema.putObject("properties");
        properties.putObject("payload").put("$ref", PAYLOAD_SCHEMA_POINTER);
        final ObjectNode forgeSchema = properties.putObject("__forge");
        forgeSchema.put("type", "object");
        forgeSchema.put("additionalProperties", false);
        final ObjectNode forgeProperties = forgeSchema.putObject("properties");
        final ObjectNode outputPortId = forgeProperties.putObject("outputPortId");
        outputPortId.put("type", "string");
        final ArrayNode allowed = outputPortId.putArray("enum");
        outputs.forEach(output -> allowed.add(output.sourcePortId().toString()));
        forgeSchema.set("required", this.objectMapper.createArrayNode().add("outputPortId"));
        schema.set("required", this.objectMapper.createArrayNode().add("payload").add("__forge"));
        return schema;
    }

    private void rewriteLocalReferences(final JsonNode node, final boolean inheritedForgeRoot) {
        if (node.isObject()) {
            final ObjectNode object = (ObjectNode) node;
            final boolean usesForgeRoot = inheritedForgeRoot && !object.has("$id");
            final JsonNode reference = object.get("$ref");
            if (usesForgeRoot && reference != null && reference.isTextual()) {
                final String value = reference.textValue();
                if ("#".equals(value)) {
                    object.put("$ref", PAYLOAD_SCHEMA_POINTER);
                } else if (value.startsWith("#/")) {
                    object.put("$ref", PAYLOAD_SCHEMA_POINTER + value.substring(1));
                }
            }
            object.fields().forEachRemaining(entry -> this.rewriteLocalReferences(entry.getValue(), usesForgeRoot));
        } else if (node.isArray()) {
            node.forEach(child -> this.rewriteLocalReferences(child, inheritedForgeRoot));
        }
    }

    private AgentExecutionResult parseExecutionResult(final String outputText,
                                                       final java.util.List<RunPort> outputs,
                                                       final boolean selectionRequired) {
        if (outputText == null || outputText.isBlank()) {
            throw new IllegalStateException("Codex output was not valid JSON.");
        }
        try {
            final JsonNode json = this.objectMapper.readTree(outputText);
            if (!selectionRequired) {
                return new AgentExecutionResult(new NodeRunOutput(this.objectMapper.writeValueAsString(json)), null);
            }
            if (json == null || !json.isObject() || !json.has("payload")) {
                throw new IllegalStateException("Codex output did not contain the required Forge routing envelope.");
            }
            final JsonNode selectedNode = json.path("__forge").path("outputPortId");
            if (!selectedNode.isTextual() || selectedNode.asText().isBlank()) {
                throw new IllegalStateException("Codex output did not select an output port.");
            }
            final UUID selected = UUID.fromString(selectedNode.asText());
            final Set<UUID> allowed = new HashSet<>();
            outputs.forEach(output -> allowed.add(output.sourcePortId()));
            if (!allowed.contains(selected)) {
                throw new IllegalStateException("Codex output selected an unknown output port.");
            }
            return new AgentExecutionResult(
                    new NodeRunOutput(this.objectMapper.writeValueAsString(json.get("payload"))),
                    selected
            );
        } catch (final JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("Codex output was not valid JSON.", e);
        }
    }
}
