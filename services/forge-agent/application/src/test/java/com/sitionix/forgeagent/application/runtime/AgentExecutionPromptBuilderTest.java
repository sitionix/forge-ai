package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentExecutionPromptBuilderTest {

    private static final UUID WORKFLOW_RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID NODE_RUN_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID AGENT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID DEPENDENCY_A = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID DEPENDENCY_B = UUID.fromString("40000000-0000-4000-8000-000000000002");
    private static final AgentOutputSchema OUTPUT_SCHEMA = AgentOutputSchema.ofCanonicalJsonObject("""
            {"type":"object","description":"Technical analysis result.","properties":{"summary":{"type":"string","description":"Concise summary."},"riskLevel":{"type":"string","description":"Technical risk level.","enum":["LOW","MEDIUM","HIGH"]}},"required":["summary","riskLevel"],"additionalProperties":false}
            """);

    private final AgentExecutionPromptBuilder builder = new AgentExecutionPromptBuilder();

    @Test
    void noDependenciesUsesStableInstructionAndInputSections() {
        final String prompt = this.builder.build(this.claim(
                "Analyze the requested change.",
                "Delete Agent support.",
                List.of()
        ));

        assertThat(prompt).contains("""
                <agent_instructions>
                Analyze the requested change.
                </agent_instructions>
                """);
        assertThat(prompt).contains("""
                <workflow_input>
                Delete Agent support.
                </workflow_input>
                """);
        assertThat(prompt).contains("""
                <dependency_results>
                None.
                </dependency_results>""");
    }

    @Test
    void dependenciesAreRenderedInClaimOrderWithSnapshotNamesIdsAndOutputs() {
        final String prompt = this.builder.build(this.claim(
                "Review.",
                "Ship change.",
                List.of(
                        new NodeDependencyOutput(DEPENDENCY_A, "Analyzer", new NodeRunOutput("{\"summary\":\"A\"}")),
                        new NodeDependencyOutput(DEPENDENCY_B, "Security Review", new NodeRunOutput("{\"risk\":\"LOW\"}"))
                )
        ));

        assertThat(prompt).containsSubsequence(
                "index=\"1\" node_run_id=\"" + DEPENDENCY_A + "\" agent_name=\"Analyzer\"",
                "{\"summary\":\"A\"}",
                "index=\"2\" node_run_id=\"" + DEPENDENCY_B + "\" agent_name=\"Security Review\"",
                "{\"risk\":\"LOW\"}"
        );
    }

    @Test
    void outputSchemaIsNotSerializedOrDuplicatedIntoPrompt() {
        final String prompt = this.builder.build(this.claim(
                "Analyze the requested change.",
                "Delete Agent support.",
                List.of()
        ));

        assertThat(prompt)
                .doesNotContain("Technical analysis result.")
                .doesNotContain("riskLevel")
                .doesNotContain("additionalProperties")
                .doesNotContain("Return only JSON")
                .doesNotContain("Use the following schema")
                .doesNotContain("summary");
    }

    private NodeExecutionClaim claim(final String instructions,
                                     final String workflowInput,
                                     final List<NodeDependencyOutput> dependencies) {
        return new NodeExecutionClaim(
                WORKFLOW_RUN_ID,
                NODE_RUN_ID,
                AGENT_ID,
                workflowInput,
                "Agent",
                instructions,
                OUTPUT_SCHEMA,
                new NodeRunExecutionModel("codex", "model-a", "medium"),
                dependencies
        );
    }
}
