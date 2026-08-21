package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.NodeRun;
import com.sitionix.forgeagent.domain.model.NodeRunExecutionModel;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.NodeRunStatus;
import com.sitionix.forgeagent.domain.model.PortDirection;
import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.RunPort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutputRoutingPolicyRegistryTest {

    private static final UUID RUN_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID NODE_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID OUTPUT_A = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID OUTPUT_B = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final UUID CONNECTION_A = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID CONNECTION_B = UUID.fromString("40000000-0000-4000-8000-000000000002");
    private static final UUID INPUT_A = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final NodeRunOutput OUTPUT = new NodeRunOutput("{\"summary\":\"done\"}");
    private static final NodeRunExecutionModel EXECUTION_MODEL = new NodeRunExecutionModel("codex", "gpt-5.6-luna", "low");

    @Test
    void selectsTerminalRoutingForTerminalNode() {
        final OutputRoutingDecision decision = this.registry(null).route(new OutputRoutingContext(null, OUTPUT, List.of(), List.of()));

        assertThat(decision).isInstanceOf(TerminalRoutingDecision.class);
    }

    @Test
    void selectsDirectRoutingForDeterministicTopology() {
        final OutputRoutingDecision decision = this.registry(null).route(new OutputRoutingContext(
                null,
                OUTPUT,
                List.of(this.output(OUTPUT_A, "Done")),
                List.of(this.connection(CONNECTION_A, OUTPUT_A))
        ));

        assertThat(decision)
                .isInstanceOfSatisfying(SelectedOutputRoutingDecision.class,
                        selected -> assertThat(selected.selectedOutputPortId()).isEqualTo(OUTPUT_A));
    }

    @Test
    void selectsDirectRoutingForOneConfiguredOutputWithoutConnections() {
        final OutputRoutingDecision decision = this.registry(null).route(new OutputRoutingContext(
                null,
                OUTPUT,
                List.of(this.output(OUTPUT_A, "Done")),
                List.of()
        ));

        assertThat(decision)
                .isInstanceOfSatisfying(SelectedOutputRoutingDecision.class,
                        selected -> assertThat(selected.selectedOutputPortId()).isEqualTo(OUTPUT_A));
    }

    @Test
    void selectsDirectRoutingForOneConfiguredOutputWithManyConnections() {
        final OutputRoutingDecision decision = this.registry(null).route(new OutputRoutingContext(
                null,
                OUTPUT,
                List.of(this.output(OUTPUT_A, "Done")),
                List.of(this.connection(CONNECTION_A, OUTPUT_A), this.connection(CONNECTION_B, OUTPUT_A))
        ));

        assertThat(decision)
                .isInstanceOfSatisfying(SelectedOutputRoutingDecision.class,
                        selected -> assertThat(selected.selectedOutputPortId()).isEqualTo(OUTPUT_A));
    }

    @Test
    void selectsAiRoutingForSemanticChoiceAndPassesStablePorts() {
        final CapturingRouter router = new CapturingRouter(OUTPUT_B);
        final OutputRoutingDecision decision = this.registry(router).route(new OutputRoutingContext(
                this.nodeRun(),
                OUTPUT,
                List.of(this.output(OUTPUT_A, "Pass"), this.output(OUTPUT_B, "Return")),
                List.of(this.connection(CONNECTION_A, OUTPUT_A), this.connection(CONNECTION_B, OUTPUT_B))
        ));

        assertThat(decision)
                .isInstanceOfSatisfying(SelectedOutputRoutingDecision.class,
                        selected -> assertThat(selected.selectedOutputPortId()).isEqualTo(OUTPUT_B));
        assertThat(router.output).isEqualTo(OUTPUT);
        assertThat(router.outputs)
                .extracting(RunPort::sourcePortId, RunPort::name, RunPort::description)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(OUTPUT_A, "Pass", "Pass description"),
                        org.assertj.core.groups.Tuple.tuple(OUTPUT_B, "Return", "Return description")
                );
        assertThat(router.executionModel).isEqualTo(EXECUTION_MODEL);
    }

    @Test
    void selectsAiRoutingForTwoConfiguredOutputsWhenOnlyOneIsConnected() {
        final CapturingRouter router = new CapturingRouter(OUTPUT_A);

        this.registry(router).route(new OutputRoutingContext(
                this.nodeRun(),
                OUTPUT,
                List.of(this.output(OUTPUT_A, "Pass"), this.output(OUTPUT_B, "Return")),
                List.of(this.connection(CONNECTION_A, OUTPUT_B))
        ));

        assertThat(router.outputs).hasSize(2);
    }

    @Test
    void selectsAiRoutingForTwoConfiguredOutputsWhenNoneAreConnected() {
        final CapturingRouter router = new CapturingRouter(OUTPUT_A);

        this.registry(router).route(new OutputRoutingContext(
                this.nodeRun(),
                OUTPUT,
                List.of(this.output(OUTPUT_A, "Pass"), this.output(OUTPUT_B, "Return")),
                List.of()
        ));

        assertThat(router.outputs).hasSize(2);
    }

    @Test
    void rejectsNullAiSelection() {
        assertThatThrownBy(() -> this.registry(new CapturingRouter(null)).route(new OutputRoutingContext(
                this.nodeRun(),
                OUTPUT,
                List.of(this.output(OUTPUT_A, "Pass"), this.output(OUTPUT_B, "Return")),
                List.of()
        )))
                .isInstanceOf(ConflictException.class)
                .extracting(exception -> ((ConflictException) exception).code())
                .isEqualTo("AI_OUTPUT_ROUTING_INVALID_PORT");
    }

    @Test
    void aiRoutingRejectsMissingSnapshottedModelInsteadOfUsingDefault() {
        assertThatThrownBy(() -> this.registry(new CapturingRouter(OUTPUT_A)).route(new OutputRoutingContext(
                this.nodeRunWithoutExecutionModel(),
                OUTPUT,
                List.of(this.output(OUTPUT_A, "Pass"), this.output(OUTPUT_B, "Return")),
                List.of()
        )))
                .isInstanceOf(ConflictException.class)
                .extracting(exception -> ((ConflictException) exception).code())
                .isEqualTo("AI_OUTPUT_ROUTING_MODEL_NOT_CONFIGURED");
    }

    @Test
    void unknownAiOutputFailsClosed() {
        final UUID unknown = UUID.fromString("99999999-9999-4999-8999-999999999999");

        assertThatThrownBy(() -> this.registry(new CapturingRouter(unknown)).route(new OutputRoutingContext(
                this.nodeRun(),
                OUTPUT,
                List.of(this.output(OUTPUT_A, "Pass"), this.output(OUTPUT_B, "Return")),
                List.of(this.connection(CONNECTION_A, OUTPUT_A), this.connection(CONNECTION_B, OUTPUT_B))
        )))
                .isInstanceOf(ConflictException.class)
                .extracting(exception -> ((ConflictException) exception).code())
                .isEqualTo("AI_OUTPUT_ROUTING_INVALID_PORT");
    }

    private OutputRoutingPolicyRegistry registry(final AiOutputRouter router) {
        return new OutputRoutingPolicyRegistry(List.of(
                new TerminalOutputRoutingPolicy(),
                new DirectOutputRoutingPolicy(),
                new AiOutputRoutingPolicy(Optional.ofNullable(router))
        ));
    }

    private RunPort output(final UUID id, final String name) {
        return new RunPort(RUN_ID, id, NODE_ID, PortDirection.OUTPUT, name, name + " description", 0);
    }

    private RunConnection connection(final UUID id, final UUID outputPortId) {
        return new RunConnection(RUN_ID, id, outputPortId, INPUT_A);
    }

    private NodeRun nodeRun() {
        return this.nodeRun(EXECUTION_MODEL);
    }

    private NodeRun nodeRunWithoutExecutionModel() {
        return this.nodeRun(null);
    }

    private NodeRun nodeRun(final NodeRunExecutionModel executionModel) {
        return new NodeRun(
                UUID.fromString("60000000-0000-4000-8000-000000000001"),
                RUN_ID,
                NODE_ID,
                UUID.fromString("70000000-0000-4000-8000-000000000001"),
                "Reviewer",
                "Review.",
                AgentOutputSchema.ofCanonicalJsonObject("{}"),
                NodeInputMode.DEPENDENCIES_ONLY,
                new NodePosition(0, 0),
                UUID.fromString("80000000-0000-4000-8000-000000000001"),
                null,
                null,
                null,
                null,
                NodeRunStatus.SUCCEEDED,
                OUTPUT,
                null,
                executionModel,
                Instant.parse("2026-08-15T00:00:00Z"),
                Instant.parse("2026-08-15T00:00:01Z"),
                Instant.parse("2026-08-15T00:00:02Z"),
                null
        );
    }

    private static final class CapturingRouter implements AiOutputRouter {

        private final UUID selected;
        private NodeRunOutput output;
        private List<RunPort> outputs;
        private NodeRunExecutionModel executionModel;

        private CapturingRouter(final UUID selected) {
            this.selected = selected;
        }

        @Override
        public UUID selectOutput(final NodeRunOutput output,
                                 final List<RunPort> outputs,
                                 final NodeRunExecutionModel executionModel) {
            this.output = output;
            this.outputs = outputs;
            this.executionModel = executionModel;
            return this.selected;
        }
    }
}
