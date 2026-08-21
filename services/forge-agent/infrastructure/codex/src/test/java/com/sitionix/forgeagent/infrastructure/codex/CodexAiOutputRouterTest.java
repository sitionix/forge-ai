package com.sitionix.forgeagent.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.PortDirection;
import com.sitionix.forgeagent.domain.model.RunPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CodexAiOutputRouterTest {

    private static final UUID RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID NODE_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID PASS_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID RETURN_ID = UUID.fromString("30000000-0000-4000-8000-000000000002");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecordingTurnClient turnClient = new RecordingTurnClient();
    private final NodeRunExecutionModel executionModel = new NodeRunExecutionModel("codex", "gpt-5.6-luna", "low");
    private final CodexAppServerProperties properties = new CodexAppServerProperties();
    private final CodexAiOutputRouter router;

    CodexAiOutputRouterTest() throws Exception {
        this.properties.setRuntimeCwd(Files.createTempDirectory("forge-agent-codex-routing").toString());
        this.router = new CodexAiOutputRouter(
                this.objectMapper,
                this.turnClient,
                new CodexRuntimeWorkspace(this.properties)
        );
    }

    @Test
    void sendsBusinessOutputAndStableOutputMetadata() throws Exception {
        this.turnClient.outputText = "{\"selectedOutputPortId\":\"" + RETURN_ID + "\"}";

        final UUID selected = this.router.selectOutput(new NodeRunOutput("{\"decision\":\"needs changes\"}"), this.outputs(), this.executionModel);

        assertThat(selected).isEqualTo(RETURN_ID);
        assertThat(this.turnClient.request.modelId()).isEqualTo("gpt-5.6-luna");
        assertThat(this.turnClient.request.effortId()).isEqualTo("low");
        final JsonNode input = this.objectMapper.readTree(this.turnClient.request.userInput());
        assertThat(input.path("businessOutput")).isEqualTo(this.objectMapper.readTree("{\"decision\":\"needs changes\"}"));
        assertThat(input.path("availableOutputs")).hasSize(2);
        assertThat(input.path("availableOutputs").get(0).path("id").asText()).isEqualTo(PASS_ID.toString());
        assertThat(input.path("availableOutputs").get(0).path("name").asText()).isEqualTo("PASS");
        assertThat(input.path("availableOutputs").get(0).path("description").asText()).isEqualTo("Review passed.");
        assertThat(this.turnClient.request.outputSchema().path("required").get(0).asText()).isEqualTo("selectedOutputPortId");
    }

    @Test
    void malformedResponseIsRejected() {
        this.turnClient.outputText = "{\"selectedOutputPortId\":42}";

        assertThatThrownBy(() -> this.router.selectOutput(new NodeRunOutput("{\"decision\":\"pass\"}"), this.outputs(), this.executionModel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AI output routing response was invalid.");
    }

    private List<RunPort> outputs() {
        return List.of(
                new RunPort(RUN_ID, PASS_ID, NODE_ID, PortDirection.OUTPUT, "PASS", "Review passed.", 0),
                new RunPort(RUN_ID, RETURN_ID, NODE_ID, PortDirection.OUTPUT, "RETURN", "Review returned feedback.", 1)
        );
    }

    private static final class RecordingTurnClient implements CodexTurnClient {
        private CodexTurnRequest request;
        private String outputText;

        @Override
        public String execute(final CodexTurnRequest request) {
            this.request = request;
            return this.outputText;
        }
    }
}
