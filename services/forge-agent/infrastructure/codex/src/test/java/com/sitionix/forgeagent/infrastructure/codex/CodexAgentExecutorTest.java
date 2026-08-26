package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;
import com.sitionix.forgeagent.application.runtime.AgentExecutionResult;
import com.sitionix.forgeagent.application.runtime.NodeExecutionClaim;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.NodeInputContribution;
import com.sitionix.forgeagent.domain.model.NodeInputEnvelope;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.PortDirection;
import com.sitionix.forgeagent.domain.model.RunPort;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CodexAgentExecutorTest {

    private static final UUID WORKFLOW_RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID NODE_RUN_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID AGENT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID INPUT_PORT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID SOURCE_NODE_RUN_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID SOURCE_CONNECTION_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID REPOSITORY_A_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID REPOSITORY_B_ID = UUID.fromString("70000000-0000-4000-8000-000000000002");
    private static final UUID OUTPUT_A_ID = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final UUID OUTPUT_B_ID = UUID.fromString("80000000-0000-4000-8000-000000000002");
    private static final AgentOutputSchema OUTPUT_SCHEMA = AgentOutputSchema.ofCanonicalJsonObject("""
            {"type":"object","description":"Technical analysis result.","properties":{"summary":{"type":"string","description":"Concise summary."},"riskLevel":{"type":"string","description":"Technical risk level.","enum":["LOW","MEDIUM","HIGH"]}},"required":["summary","riskLevel"],"additionalProperties":false}
            """);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecordingCodexClient client = new RecordingCodexClient();
    private final CodexAgentExecutor executor = new CodexAgentExecutor(
            this.objectMapper,
            this.client
    );

    @Test
    void usesClaimModelEffortAndExactParsedOutputSchema() throws Exception {
        this.client.outputText = "{\n  \"summary\": \"Done\",\n  \"riskLevel\": \"MEDIUM\"\n}";

        final AgentExecutionResult output = this.executor.execute(this.claim(new NodeRunExecutionModel("codex", "gpt-5.6-luna", "high"), OUTPUT_SCHEMA));

        assertThat(this.client.request.modelId()).isEqualTo("gpt-5.6-luna");
        assertThat(this.client.request.effortId()).isEqualTo("high");
        assertThat(this.client.request.outputSchema()).isEqualTo(this.objectMapper.readTree(OUTPUT_SCHEMA.jsonObject()));
        assertThat(this.client.request.outputSchema().path("description").asText()).isEqualTo("Technical analysis result.");
        assertThat(this.client.request.outputSchema().path("properties").path("summary").path("description").asText()).isEqualTo("Concise summary.");
        assertThat(this.client.request.executionWorkspace()).isEqualTo(this.workspace());
        assertThat(output).isEqualTo(new AgentExecutionResult(new NodeRunOutput("{\"summary\":\"Done\",\"riskLevel\":\"MEDIUM\"}"), null));
    }

    @Test
    void multiOutputUsesOneTurnWithCollisionSafeEffectiveSchemaAndStripsForgeMetadata() throws Exception {
        this.client.outputText = """
                {"payload":{"summary":"Done","riskLevel":"LOW"},"__forge":{"outputPortId":"%s"}}
                """.formatted(OUTPUT_B_ID);

        final AgentExecutionResult result = this.executor.execute(this.multiOutputClaim());

        assertThat(this.client.executeCount).isEqualTo(1);
        final JsonNode schema = this.client.request.outputSchema();
        assertThat(schema.path("required")).containsExactly(
                this.objectMapper.getNodeFactory().textNode("payload"),
                this.objectMapper.getNodeFactory().textNode("__forge")
        );
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("properties").path("payload").path("$ref").asText())
                .isEqualTo("#/$defs/__forge_payload");
        final JsonNode payloadSchema = schema.path("$defs").path("__forge_payload");
        assertThat(payloadSchema.path("$id").asText()).isEqualTo("urn:forge:agent-output-payload:" + NODE_RUN_ID);
        assertThat(payloadSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(payloadSchema.path("properties").path("summary").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("__forge").path("properties").path("outputPortId").path("enum"))
                .containsExactly(
                        this.objectMapper.getNodeFactory().textNode(OUTPUT_A_ID.toString()),
                        this.objectMapper.getNodeFactory().textNode(OUTPUT_B_ID.toString())
                );
        final JsonNode input = this.objectMapper.readTree(this.client.request.userInput());
        assertThat(input.path("availableOutputs")).hasSize(2);
        assertThat(input.path("availableOutputs").get(0).path("name").asText()).isEqualTo("Pass");
        assertThat(input.path("availableOutputs").get(1).path("description").asText()).isEqualTo("Return for changes");
        assertThat(result.output()).isEqualTo(new NodeRunOutput("{\"summary\":\"Done\",\"riskLevel\":\"LOW\"}"));
        assertThat(result.output().jsonValue()).doesNotContain("__forge", "outputPortId");
        assertThat(result.selectedOutputPortId()).isEqualTo(OUTPUT_B_ID);
    }

    @Test
    void multiOutputPreservesLocalDefinitionReferencesInDedicatedPayloadResource() throws Exception {
        final AgentOutputSchema schemaWithDefinitions = AgentOutputSchema.ofCanonicalJsonObject("""
                {"type":"object","properties":{"steps":{"type":"array","items":{"$ref":"#/$defs/step"}}},"required":["steps"],"additionalProperties":false,"$defs":{"step":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"],"additionalProperties":false}}}
                """);
        final JsonNode originalSchema = this.objectMapper.readTree(schemaWithDefinitions.jsonObject());
        this.client.outputText = """
                {"payload":{"steps":[{"name":"compile"}]},"__forge":{"outputPortId":"%s"}}
                """.formatted(OUTPUT_A_ID);

        final AgentExecutionResult result = this.executor.execute(this.multiOutputClaim(schemaWithDefinitions));

        final JsonNode effective = this.client.request.outputSchema();
        final JsonNode payloadResource = effective.path("$defs").path("__forge_payload");
        assertThat(effective.path("properties").path("payload").path("$ref").asText())
                .isEqualTo("#/$defs/__forge_payload");
        assertThat(payloadResource.path("$id").asText()).isEqualTo("urn:forge:agent-output-payload:" + NODE_RUN_ID);
        assertThat(payloadResource.path("properties").path("steps").path("items").path("$ref").asText())
                .isEqualTo("#/$defs/step");
        assertThat(payloadResource.path("$defs").path("step").path("properties").path("name").path("type").asText())
                .isEqualTo("string");
        assertThat(this.objectMapper.readTree(schemaWithDefinitions.jsonObject())).isEqualTo(originalSchema);
        assertThat(result.output().jsonValue()).isEqualTo("{\"steps\":[{\"name\":\"compile\"}]}");
        assertThat(result.selectedOutputPortId()).isEqualTo(OUTPUT_A_ID);
        assertThat(this.client.executeCount).isEqualTo(1);
    }

    @Test
    void multiOutputRootReferenceRecursesWithinPayloadResource() {
        final AgentOutputSchema recursiveSchema = AgentOutputSchema.ofCanonicalJsonObject("""
                {"type":"object","properties":{"value":{"type":"string"},"child":{"anyOf":[{"$ref":"#"},{"type":"null"}]}},"required":["value","child"],"additionalProperties":false}
                """);
        this.client.outputText = """
                {"payload":{"value":"root","child":null},"__forge":{"outputPortId":"%s"}}
                """.formatted(OUTPUT_B_ID);

        this.executor.execute(this.multiOutputClaim(recursiveSchema));

        final JsonNode effective = this.client.request.outputSchema();
        final JsonNode payloadResource = effective.path("$defs").path("__forge_payload");
        assertThat(payloadResource.path("$id").asText()).isEqualTo("urn:forge:agent-output-payload:" + NODE_RUN_ID);
        assertThat(payloadResource.path("properties").path("child").path("anyOf").get(0).path("$ref").asText())
                .isEqualTo("#");
        assertThat(payloadResource.path("properties").has("__forge")).isFalse();
        assertThat(this.client.executeCount).isEqualTo(1);
    }

    @Test
    void forgeDefinitionCannotCollideWithSameNamedUserDefinition() {
        final AgentOutputSchema collidingSchema = AgentOutputSchema.ofCanonicalJsonObject("""
                {"type":"object","properties":{"value":{"$ref":"#/$defs/__forge_payload"}},"required":["value"],"additionalProperties":false,"$defs":{"__forge_payload":{"type":"string"}}}
                """);
        this.client.outputText = """
                {"payload":{"value":"business"},"__forge":{"outputPortId":"%s"}}
                """.formatted(OUTPUT_A_ID);

        this.executor.execute(this.multiOutputClaim(collidingSchema));

        final JsonNode effective = this.client.request.outputSchema();
        final JsonNode payloadResource = effective.path("$defs").path("__forge_payload");
        assertThat(payloadResource.path("properties").path("value").path("$ref").asText())
                .isEqualTo("#/$defs/__forge_payload");
        assertThat(payloadResource.path("$defs").path("__forge_payload").path("type").asText()).isEqualTo("string");
        assertThat(effective.path("$defs").size()).isEqualTo(1);
    }

    @Test
    void multiOutputFirstTurnExplainsGenericRoutingContractWithoutDomainSemantics() {
        this.client.outputText = """
                {"payload":{"summary":"Done","riskLevel":"LOW"},"__forge":{"outputPortId":"%s"}}
                """.formatted(OUTPUT_A_ID);

        this.executor.execute(this.multiOutputClaim());

        assertThat(this.client.request.developerInstructions()).contains(
                "`availableOutputs`, when present, contains the output choices for this invocation.",
                "`name` and `description` define its business meaning",
                "choose exactly one according to the actual business result",
                "`__forge.outputPortId`",
                "`payload` contains only the configured business output",
                "`__forge` is workflow control metadata, not business data"
        ).doesNotContain(
                "Reviewer",
                "review approval",
                "skill",
                "Pass when",
                "Return when"
        );
        assertThat(this.client.executeCount).isEqualTo(1);
    }

    @Test
    void multiOutputMissingSelectionFailsClosedAfterExactlyOneTurn() {
        this.client.outputText = "{\"payload\":{\"summary\":\"Done\",\"riskLevel\":\"LOW\"},\"__forge\":{}}";

        assertThatThrownBy(() -> this.executor.execute(this.multiOutputClaim()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Codex output did not select an output port.");
        assertThat(this.client.executeCount).isEqualTo(1);
    }

    @Test
    void multiOutputUnknownSelectionFailsClosedAfterExactlyOneTurn() {
        this.client.outputText = """
                {"payload":{"summary":"Done","riskLevel":"LOW"},"__forge":{"outputPortId":"99999999-9999-4999-8999-999999999999"}}
                """;

        assertThatThrownBy(() -> this.executor.execute(this.multiOutputClaim()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Codex output selected an unknown output port.");
        assertThat(this.client.executeCount).isEqualTo(1);
    }

    @Test
    void prefixesGenericWorkflowContractBeforeSnapshotInstructionsAndKeepsStructuredUserInput() throws Exception {
        final NodeExecutionClaim claim = new NodeExecutionClaim(
                WORKFLOW_RUN_ID,
                NODE_RUN_ID,
                AGENT_ID,
                "Review auth changes.",
                "Analyzer",
                "Analyze the requested change.",
                OUTPUT_SCHEMA,
                new NodeRunExecutionModel("codex", "gpt-5.6-luna", null),
                new NodeInputEnvelope(
                        "Review auth changes.",
                        new RunPort(WORKFLOW_RUN_ID, INPUT_PORT_ID, UUID.randomUUID(), PortDirection.INPUT, "Review feedback", "Reviewer feedback.", 0),
                        List.of(new NodeInputContribution(
                                SOURCE_NODE_RUN_ID,
                                SOURCE_CONNECTION_ID,
                                new NodeRunOutput("{\"risk\":\"LOW\"}"),
                                null
                        ))
                ),
                List.of(),
                this.workspace()
        );

        this.executor.execute(claim);

        assertThat(this.client.request.developerInstructions())
                .startsWith(WorkflowExecutionDeveloperInstructions.CONTRACT)
                .endsWith("\nAnalyze the requested change.");
        assertThat(this.client.request.developerInstructions().substring(
                WorkflowExecutionDeveloperInstructions.CONTRACT.length() + 1
        )).isEqualTo("Analyze the requested change.");
        final JsonNode userInput = this.objectMapper.readTree(this.client.request.userInput());
        assertThat(userInput.path("task").asText()).isEqualTo("Review auth changes.");
        assertThat(userInput.path("entryInput").path("id").asText()).isEqualTo(INPUT_PORT_ID.toString());
        assertThat(userInput.path("entryInput").path("name").asText()).isEqualTo("Review feedback");
        assertThat(userInput.path("entryInput").path("description").asText()).isEqualTo("Reviewer feedback.");
        assertThat(userInput.path("contributions")).hasSize(1);
        assertThat(userInput.path("contributions").get(0).path("sourceNodeRunId").asText()).isEqualTo(SOURCE_NODE_RUN_ID.toString());
        assertThat(userInput.path("contributions").get(0).path("sourceConnectionId").asText()).isEqualTo(SOURCE_CONNECTION_ID.toString());
        assertThat(userInput.path("contributions").get(0).path("payload")).isEqualTo(this.objectMapper.readTree("{\"risk\":\"LOW\"}"));
        assertThat(this.client.request.userInput()).doesNotContain(
                "Analyze the requested change.",
                "outputSchema",
                "nodeRunId",
                "nextTask",
                "nextPrompt"
        );
    }

    @Test
    void genericWorkflowContractDoesNotContainDomainSpecificFieldOrAgentRules() {
        assertThat(WorkflowExecutionDeveloperInstructions.CONTRACT).doesNotContain(
                "summ",
                "actualValue",
                "threshold",
                "Reviewer",
                "if Reviewer",
                "Agent name",
                "port name"
        );
    }

    @Test
    void dependencyOnlyEnvelopeOmitsTaskWhenPolicyOmittedIt() throws Exception {
        final NodeExecutionClaim claim = new NodeExecutionClaim(
                WORKFLOW_RUN_ID,
                NODE_RUN_ID,
                AGENT_ID,
                "Review auth changes.",
                "Analyzer",
                "Analyze the requested change.",
                OUTPUT_SCHEMA,
                new NodeRunExecutionModel("codex", "gpt-5.6-luna", null),
                new NodeInputEnvelope(
                        null,
                        new RunPort(WORKFLOW_RUN_ID, INPUT_PORT_ID, UUID.randomUUID(), PortDirection.INPUT, "Review feedback", "Reviewer feedback.", 0),
                        List.of(new NodeInputContribution(
                                SOURCE_NODE_RUN_ID,
                                SOURCE_CONNECTION_ID,
                                new NodeRunOutput("{\"risk\":\"LOW\"}"),
                                null
                        ))
                ),
                List.of(),
                this.workspace()
        );

        this.executor.execute(claim);

        final JsonNode userInput = this.objectMapper.readTree(this.client.request.userInput());
        assertThat(userInput.has("task")).isFalse();
        assertThat(userInput.path("entryInput").path("id").asText()).isEqualTo(INPUT_PORT_ID.toString());
        assertThat(userInput.path("contributions")).hasSize(1);
        assertThat(userInput.path("contributions").get(0).path("payload")).isEqualTo(this.objectMapper.readTree("{\"risk\":\"LOW\"}"));
    }

    @Test
    void contributionRepositoryProvenanceDoesNotOverrideResolvedGlobalWorkspace() {
        final ExecutionWorkspace globalWorkspace = new ExecutionWorkspace(
                Path.of("/forge/project"),
                List.of(Path.of("/forge/project/backend"), Path.of("/forge/project/frontend"))
        );
        final NodeExecutionClaim claim = new NodeExecutionClaim(
                WORKFLOW_RUN_ID,
                NODE_RUN_ID,
                AGENT_ID,
                "Integrate repository outputs.",
                "Integrator",
                "Integrate the contributions.",
                OUTPUT_SCHEMA,
                new NodeRunExecutionModel("codex", "gpt-5.6-luna", null),
                new NodeInputEnvelope(
                        null,
                        null,
                        List.of(
                                new NodeInputContribution(
                                        UUID.randomUUID(), UUID.randomUUID(), new NodeRunOutput("{}"), REPOSITORY_A_ID),
                                new NodeInputContribution(
                                        UUID.randomUUID(), UUID.randomUUID(), new NodeRunOutput("{}"), REPOSITORY_B_ID)
                        )
                ),
                List.of(),
                globalWorkspace
        );

        this.executor.execute(claim);

        assertThat(this.client.request.executionWorkspace()).isEqualTo(globalWorkspace);
    }

    @Test
    void noDependenciesAreSerializedAsEmptyArray() throws Exception {
        this.executor.execute(this.claim(new NodeRunExecutionModel("codex", "gpt-5.6-luna", null), OUTPUT_SCHEMA));

        final JsonNode userInput = this.objectMapper.readTree(this.client.request.userInput());
        assertThat(userInput.path("task").asText()).isEqualTo("Review auth changes.");
        assertThat(userInput.path("contributions")).isEmpty();
    }

    @Test
    void nullEffortRemainsNull() {
        this.executor.execute(this.claim(new NodeRunExecutionModel("codex", "gpt-5.6-luna", null), OUTPUT_SCHEMA));

        assertThat(this.client.request.effortId()).isNull();
    }

    @Test
    void invalidReturnedJsonFailsSafely() {
        this.client.outputText = "not-json";

        assertThatThrownBy(() -> this.executor.execute(this.claim(new NodeRunExecutionModel("codex", "m", null), OUTPUT_SCHEMA)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Codex output was not valid JSON.");
    }

    @Test
    void unsupportedProviderFailsSafelyBeforeTurnExecution() {
        assertThatThrownBy(() -> this.executor.execute(this.claim(new NodeRunExecutionModel("other", "m", null), OUTPUT_SCHEMA)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Agent provider is not supported.");
        assertThat(this.client.request).isNull();
    }

    @Test
    void invalidPersistedSchemaJsonFailsSafelyBeforeTurnExecution() {
        final AgentOutputSchema malformed = AgentOutputSchema.ofCanonicalJsonObject("{\"type\":\"object\",\"bad\":}");

        assertThatThrownBy(() -> this.executor.execute(this.claim(new NodeRunExecutionModel("codex", "m", null), malformed)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Agent output schema is invalid.");
        assertThat(this.client.request).isNull();
    }

    private NodeExecutionClaim claim(final NodeRunExecutionModel executionModel, final AgentOutputSchema outputSchema) {
        return new NodeExecutionClaim(
                WORKFLOW_RUN_ID,
                NODE_RUN_ID,
                AGENT_ID,
                "Review auth changes.",
                "Analyzer",
                "Analyze the requested change.",
                outputSchema,
                executionModel,
                new NodeInputEnvelope("Review auth changes.", null, List.of()),
                List.of(),
                this.workspace()
        );
    }

    private NodeExecutionClaim multiOutputClaim() {
        return this.multiOutputClaim(OUTPUT_SCHEMA);
    }

    private NodeExecutionClaim multiOutputClaim(final AgentOutputSchema outputSchema) {
        final NodeExecutionClaim base = this.claim(new NodeRunExecutionModel("codex", "gpt-5.6-luna", "high"), outputSchema);
        return new NodeExecutionClaim(
                base.workflowRunId(), base.nodeRunId(), base.sourceAgentId(), base.workflowInput(), base.agentName(),
                base.agentInstructions(), base.outputSchema(), base.executionModel(), base.inputEnvelope(),
                List.of(
                        new RunPort(WORKFLOW_RUN_ID, OUTPUT_A_ID, UUID.randomUUID(), PortDirection.OUTPUT,
                                "Pass", "Continue the workflow", 0),
                        new RunPort(WORKFLOW_RUN_ID, OUTPUT_B_ID, UUID.randomUUID(), PortDirection.OUTPUT,
                                "Return", "Return for changes", 1)
                ),
                base.executionWorkspace()
        );
    }

    private ExecutionWorkspace workspace() {
        return new ExecutionWorkspace(Path.of("/forge/project/backend"), List.of(Path.of("/forge/project/backend")));
    }

    private static final class RecordingCodexClient implements CodexClient {
        private CodexTurnRequest request;
        private String outputText = "{\"summary\":\"Done\",\"riskLevel\":\"LOW\"}";
        private int executeCount;

        @Override
        public String execute(final CodexTurnRequest request) {
            this.executeCount++;
            this.request = request;
            return this.outputText;
        }

        @Override
        public String version() {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode request(final String method, final JsonNode params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }
}
