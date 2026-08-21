package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;
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

        final NodeRunOutput output = this.executor.execute(this.claim(new NodeRunExecutionModel("codex", "gpt-5.6-luna", "high"), OUTPUT_SCHEMA));

        assertThat(this.client.request.modelId()).isEqualTo("gpt-5.6-luna");
        assertThat(this.client.request.effortId()).isEqualTo("high");
        assertThat(this.client.request.outputSchema()).isEqualTo(this.objectMapper.readTree(OUTPUT_SCHEMA.jsonObject()));
        assertThat(this.client.request.outputSchema().path("description").asText()).isEqualTo("Technical analysis result.");
        assertThat(this.client.request.outputSchema().path("properties").path("summary").path("description").asText()).isEqualTo("Concise summary.");
        assertThat(this.client.request.executionWorkspace()).isEqualTo(this.workspace());
        assertThat(output).isEqualTo(new NodeRunOutput("{\"summary\":\"Done\",\"riskLevel\":\"MEDIUM\"}"));
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
                this.workspace()
        );
    }

    private ExecutionWorkspace workspace() {
        return new ExecutionWorkspace(Path.of("/forge/project/backend"), List.of(Path.of("/forge/project/backend")));
    }

    private static final class RecordingCodexClient implements CodexClient {
        private CodexTurnRequest request;
        private String outputText = "{\"summary\":\"Done\",\"riskLevel\":\"LOW\"}";

        @Override
        public String execute(final CodexTurnRequest request) {
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
