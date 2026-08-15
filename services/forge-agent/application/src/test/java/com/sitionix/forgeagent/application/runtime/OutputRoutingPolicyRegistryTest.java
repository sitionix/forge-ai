package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.NodeRunOutput;
import com.sitionix.forgeagent.domain.model.PortDirection;
import com.sitionix.forgeagent.domain.model.RunConnection;
import com.sitionix.forgeagent.domain.model.RunPort;
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
                null,
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
    }

    @Test
    void selectsAiRoutingForTwoConfiguredOutputsWhenOnlyOneIsConnected() {
        final CapturingRouter router = new CapturingRouter(OUTPUT_A);

        this.registry(router).route(new OutputRoutingContext(
                null,
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
                null,
                OUTPUT,
                List.of(this.output(OUTPUT_A, "Pass"), this.output(OUTPUT_B, "Return")),
                List.of()
        ));

        assertThat(router.outputs).hasSize(2);
    }

    @Test
    void rejectsNullAiSelection() {
        assertThatThrownBy(() -> this.registry(new CapturingRouter(null)).route(new OutputRoutingContext(
                null,
                OUTPUT,
                List.of(this.output(OUTPUT_A, "Pass"), this.output(OUTPUT_B, "Return")),
                List.of()
        )))
                .isInstanceOf(ConflictException.class)
                .extracting(exception -> ((ConflictException) exception).code())
                .isEqualTo("AI_OUTPUT_ROUTING_INVALID_PORT");
    }

    @Test
    void unknownAiOutputFailsClosed() {
        final UUID unknown = UUID.fromString("99999999-9999-4999-8999-999999999999");

        assertThatThrownBy(() -> this.registry(new CapturingRouter(unknown)).route(new OutputRoutingContext(
                null,
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

    private static final class CapturingRouter implements AiOutputRouter {

        private final UUID selected;
        private NodeRunOutput output;
        private List<RunPort> outputs;

        private CapturingRouter(final UUID selected) {
            this.selected = selected;
        }

        @Override
        public UUID selectOutput(final NodeRunOutput output, final List<RunPort> outputs) {
            this.output = output;
            this.outputs = outputs;
            return this.selected;
        }
    }
}
