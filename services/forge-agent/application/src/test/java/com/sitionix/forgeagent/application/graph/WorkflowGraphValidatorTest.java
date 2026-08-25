package com.sitionix.forgeagent.application.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentDefinition;
import com.sitionix.forgeagent.domain.model.AgentOutputSchema;
import com.sitionix.forgeagent.domain.model.Node;
import com.sitionix.forgeagent.domain.model.NodeInputMode;
import com.sitionix.forgeagent.domain.model.NodePort;
import com.sitionix.forgeagent.domain.model.NodePosition;
import com.sitionix.forgeagent.domain.model.WorkflowConnection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowGraphValidatorTest {

    private static final AgentOutputSchema OUTPUT_SCHEMA = AgentOutputSchema.ofCanonicalJsonObject("{}");

    private final WorkflowGraphValidator validator = new WorkflowGraphValidator();
    private final UUID projectId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID otherProjectId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID nodeA = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private final UUID nodeB = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private final UUID nodeC = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private final UUID nodeD = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
    private final UUID agentA = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private final UUID agentB = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
    private final UUID inputA = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private final UUID inputB = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private final UUID inputC = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private final UUID inputD = UUID.fromString("10000000-0000-4000-8000-000000000004");
    private final UUID outputA = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private final UUID outputB = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private final UUID outputC = UUID.fromString("20000000-0000-4000-8000-000000000003");
    private final UUID outputD = UUID.fromString("20000000-0000-4000-8000-000000000004");
    private final UUID connectionAB = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private final UUID connectionAC = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private final UUID connectionBC = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private final UUID connectionCD = UUID.fromString("30000000-0000-4000-8000-000000000004");
    private final UUID connectionDC = UUID.fromString("30000000-0000-4000-8000-000000000005");

    @Test
    void validOutputToInputConnection() {
        final WorkflowGraphValidator.ValidatedGraph graph = this.validate(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA)),
                        this.node(this.nodeB, this.agentB, List.of(this.inputB), List.of(this.outputB))),
                List.of(this.connection(this.connectionAB, this.outputA, this.inputB))
        );

        assertThat(graph.connections()).containsExactly(this.connection(this.connectionAB, this.outputA, this.inputB));
    }

    @Test
    void acceptsReachableLinearGraphFromTaskInput() {
        final WorkflowGraphValidator.ValidatedGraph graph = this.validate(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA)),
                        this.node(this.nodeB, this.agentB, List.of(this.inputB), List.of(this.outputB)),
                        this.node(this.nodeC, this.agentA, List.of(this.inputC), List.of(this.outputC))),
                List.of(this.connection(this.connectionAB, this.outputA, this.inputB),
                        this.connection(this.connectionBC, this.outputB, this.inputC)),
                this.inputA
        );

        assertThat(graph.nodes()).hasSize(3);
    }

    @Test
    void acceptsReachableCycleFromTaskInput() {
        final WorkflowGraphValidator.ValidatedGraph graph = this.validate(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA)),
                        this.node(this.nodeB, this.agentB, List.of(this.inputB), List.of(this.outputB)),
                        this.node(this.nodeC, this.agentA, List.of(this.inputC), List.of(this.outputC, this.outputD))),
                List.of(this.connection(this.connectionAB, this.outputA, this.inputB),
                        this.connection(this.connectionBC, this.outputB, this.inputC),
                        this.connection(this.connectionCD, this.outputC, this.inputA)),
                this.inputA
        );

        assertThat(graph.connections()).hasSize(3);
    }

    @Test
    void rejectsDisconnectedNodeFromTaskInput() {
        this.expectInconsistentGraphError(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA)),
                        this.node(this.nodeB, this.agentB, List.of(this.inputB), List.of(this.outputB)),
                        this.node(this.nodeC, this.agentA, List.of(this.inputC), List.of(this.outputC))),
                List.of(this.connection(this.connectionAB, this.outputA, this.inputB))
        );
    }

    @Test
    void rejectsDisconnectedCyclicIslandFromTaskInput() {
        this.expectInconsistentGraphError(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA)),
                        this.node(this.nodeB, this.agentB, List.of(this.inputB), List.of(this.outputB)),
                        this.node(this.nodeC, this.agentA, List.of(this.inputC), List.of(this.outputC)),
                        this.node(this.nodeD, this.agentB, List.of(this.inputD), List.of(this.outputD))),
                List.of(this.connection(this.connectionAB, this.outputA, this.inputB),
                        this.connection(this.connectionCD, this.outputC, this.inputD),
                        this.connection(this.connectionDC, this.outputD, this.inputC))
        );
    }

    @Test
    void acceptsReachableTerminalNodeWithoutOutgoingConnections() {
        final WorkflowGraphValidator.ValidatedGraph graph = this.validate(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA)),
                        this.node(this.nodeB, this.agentB, List.of(this.inputB), List.of(this.outputB))),
                List.of(this.connection(this.connectionAB, this.outputA, this.inputB)),
                this.inputA
        );

        assertThat(graph.nodes()).hasSize(2);
    }

    @Test
    void nullOutputPortListNormalizesToEmptyList() {
        final Node node = new Node(
                this.nodeB,
                this.agentA,
                NodeInputMode.DEPENDENCIES_ONLY,
                List.of(this.port(this.inputB, "Input", "Input description.", 0)),
                null,
                new NodePosition(1.0, 2.0),
                com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
        );

        final WorkflowGraphValidator.ValidatedGraph graph = this.validate(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA, this.outputB)), node),
                List.of(this.connection(this.connectionAB, this.outputA, this.inputB)),
                this.inputA,
                this.outputB
        );

        assertThat(graph.nodes().get(1).inputs()).containsExactly(this.port(this.inputB, "Input", "Input description.", 0));
        assertThat(graph.nodes().get(1).outputs()).isEmpty();
    }

    @Test
    void validatesMultipleInputsAndOutputsWithIndependentNamesAndOrders() {
        final Node node = new Node(
                this.nodeA,
                this.agentA,
                NodeInputMode.DEPENDENCIES_ONLY,
                List.of(this.port(this.inputB, " Context ", " Context description. ", 1),
                        this.port(this.inputA, "Result", "Input result.", 0)),
                List.of(this.port(this.outputA, "Result", "Output result.", 0),
                        this.port(this.outputB, "Return", "Needs changes.", 1),
                        this.port(this.outputC, "Reject", "Reject the work.", 2)),
                new NodePosition(1.0, 2.0),
                com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
        );

        final WorkflowGraphValidator.ValidatedGraph graph = this.validate(List.of(node), List.of());

        assertThat(graph.nodes().getFirst().inputs())
                .extracting(NodePort::id, NodePort::name, NodePort::description, NodePort::order)
                .containsExactly(
                        tuple(this.inputA, "Result", "Input result.", 0),
                        tuple(this.inputB, "Context", "Context description.", 1)
                );
        assertThat(graph.nodes().getFirst().outputs())
                .extracting(NodePort::name, NodePort::order)
                .containsExactly(tuple("Result", 0), tuple("Return", 1), tuple("Reject", 2));
    }

    @Test
    void rejectsInvalidPorts() {
        this.expectPortError(
                new Node(
                        this.nodeA,
                        this.agentA,
                        NodeInputMode.DEPENDENCIES_ONLY,
                        List.of(this.port(this.inputA, "Approved", "One.", 0), this.port(this.inputB, "Approved", "Two.", 1)),
                        List.of(),
                        new NodePosition(0, 0),
                        com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
                ),
                "DUPLICATE_NODE_PORT_NAME"
        );
        this.expectPortError(
                new Node(
                        this.nodeA,
                        this.agentA,
                        NodeInputMode.DEPENDENCIES_ONLY,
                        List.of(),
                        List.of(this.port(this.outputA, "Approved", "One.", 0), this.port(this.outputB, "Approved", "Two.", 1)),
                        new NodePosition(0, 0),
                        com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
                ),
                "DUPLICATE_NODE_PORT_NAME"
        );
        this.expectPortError(
                new Node(
                        this.nodeA,
                        this.agentA,
                        NodeInputMode.DEPENDENCIES_ONLY,
                        List.of(this.port(this.inputA, "Review", "One.", 0)),
                        List.of(this.port(this.inputA, "Approved", "Two.", 0)),
                        new NodePosition(0, 0),
                        com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
                ),
                "DUPLICATE_NODE_PORT_ID"
        );
        this.expectPortError(new Node(
                this.nodeA,
                this.agentA,
                NodeInputMode.DEPENDENCIES_ONLY,
                List.of(this.port(this.inputA, " ", "Description.", 0)),
                List.of(),
                new NodePosition(0, 0),
                com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
        ), "INVALID_NODE_PORT");
        this.expectPortError(new Node(
                this.nodeA,
                this.agentA,
                NodeInputMode.DEPENDENCIES_ONLY,
                List.of(this.port(this.inputA, "Review", " ", 0)),
                List.of(),
                new NodePosition(0, 0),
                com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
        ), "INVALID_NODE_PORT");
        this.expectPortError(new Node(
                this.nodeA,
                this.agentA,
                NodeInputMode.DEPENDENCIES_ONLY,
                List.of(this.port(this.inputA, "Review", "Description.", -1)),
                List.of(),
                new NodePosition(0, 0),
                com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
        ), "INVALID_NODE_PORT_ORDER");
        this.expectPortError(new Node(
                this.nodeA,
                this.agentA,
                NodeInputMode.DEPENDENCIES_ONLY,
                List.of(this.port(this.inputA, "Review", "One.", 0), this.port(this.inputB, "Context", "Two.", 0)),
                List.of(),
                new NodePosition(0, 0),
                com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
        ), "INVALID_NODE_PORT_ORDER");
        this.expectPortError(new Node(
                this.nodeA,
                this.agentA,
                NodeInputMode.DEPENDENCIES_ONLY,
                List.of(this.port(this.inputA, "Review", "One.", 0), this.port(this.inputB, "Context", "Two.", 2)),
                List.of(),
                new NodePosition(0, 0),
                com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
        ), "INVALID_NODE_PORT_ORDER");
    }

    @Test
    void rejectsConnectionShapeErrors() {
        this.expectConnectionError(List.of(this.connection(this.connectionAB, UUID.fromString("99999999-9999-4999-8999-999999999999"), this.inputB)), "UNKNOWN_SOURCE_OUTPUT_PORT");
        this.expectConnectionError(List.of(this.connection(this.connectionAB, this.outputA, UUID.fromString("99999999-9999-4999-8999-999999999999"))), "UNKNOWN_TARGET_INPUT_PORT");
        this.expectConnectionError(List.of(this.connection(this.connectionAB, this.inputA, this.inputB)), "INVALID_SOURCE_OUTPUT_PORT");
        this.expectConnectionError(List.of(this.connection(this.connectionAB, this.outputA, this.outputB)), "INVALID_TARGET_INPUT_PORT");
        this.expectConnectionError(List.of(this.connection(this.connectionAB, this.outputA, this.inputA)), "UNGUARDED_SELF_NODE_CONNECTION");
        this.expectConnectionError(List.of(this.connection(this.connectionAB, this.outputA, this.inputB),
                this.connection(this.connectionAB, this.outputA, this.inputC)), "DUPLICATE_WORKFLOW_CONNECTION_ID");
        this.expectConnectionError(List.of(this.connection(this.connectionAB, this.outputA, this.inputB),
                this.connection(this.connectionAC, this.outputA, this.inputB)), "DUPLICATE_WORKFLOW_CONNECTION");
    }

    @Test
    void acceptsSelfLoopWhenItsInputAlsoHasAnExternalDependency() {
        final WorkflowGraphValidator.ValidatedGraph graph = this.validate(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA)),
                        this.node(this.nodeB, this.agentB, List.of(this.inputB), List.of(this.outputB, this.outputC))),
                List.of(this.connection(this.connectionAB, this.outputB, this.inputA),
                        this.connection(this.connectionAC, this.outputA, this.inputA)),
                this.inputB,
                this.outputC
        );

        assertThat(graph.connections()).hasSize(2);
    }

    @Test
    void rejectsGraphAfterLastExternalDependencyOfSelfLoopIsRemoved() {
        this.expectConnectionError(
                List.of(this.connection(this.connectionAC, this.outputA, this.inputA)),
                "UNGUARDED_SELF_NODE_CONNECTION"
        );
    }

    @Test
    void supportsFanOutFanInAndDistinctEdgesBetweenSameNodes() {
        final WorkflowGraphValidator.ValidatedGraph graph = this.validate(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA, this.outputB)),
                        this.node(this.nodeB, this.agentB, List.of(this.inputB), List.of(this.outputC)),
                        this.node(this.nodeC, this.agentA, List.of(this.inputC), List.of(this.outputD))),
                List.of(this.connection(this.connectionAB, this.outputA, this.inputB),
                        this.connection(this.connectionAC, this.outputA, this.inputC),
                        this.connection(this.connectionBC, this.outputC, this.inputC),
                        this.connection(UUID.fromString("30000000-0000-4000-8000-000000000004"), this.outputB, this.inputB))
        );

        assertThat(graph.connections()).hasSize(4);
    }

    @Test
    void acceptsMultiNodeCyclesForRuntimeReentry() {
        final WorkflowGraphValidator.ValidatedGraph direct = this.validate(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA, this.outputC)),
                        this.node(this.nodeB, this.agentB, List.of(this.inputB), List.of(this.outputB))),
                List.of(this.connection(this.connectionAB, this.outputA, this.inputB),
                        this.connection(this.connectionBC, this.outputB, this.inputA))
        );
        final WorkflowGraphValidator.ValidatedGraph indirect = this.validate(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA)),
                        this.node(this.nodeB, this.agentB, List.of(this.inputB), List.of(this.outputB)),
                        this.node(this.nodeC, this.agentA, List.of(this.inputC), List.of(this.outputC, this.outputD))),
                List.of(this.connection(this.connectionAB, this.outputA, this.inputB),
                        this.connection(this.connectionBC, this.outputB, this.inputC),
                        this.connection(this.connectionAC, this.outputC, this.inputA))
        );

        assertThat(direct.connections()).hasSize(2);
        assertThat(indirect.connections()).hasSize(3);
    }

    @Test
    void portRenameDoesNotAffectConnectionIdentity() {
        final WorkflowGraphValidator.ValidatedGraph graph = this.validate(
                List.of(new Node(
                        this.nodeA,
                        this.agentA,
                        NodeInputMode.DEPENDENCIES_ONLY,
                        List.of(this.port(this.inputA, "Input", "Input description.", 0)),
                        List.of(this.port(this.outputA, "Ready for testing", "Renamed.", 0)),
                        new NodePosition(0, 0),
                        com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
                ),
                        this.node(this.nodeB, this.agentB, List.of(this.inputB), List.of(this.outputB))),
                List.of(this.connection(this.connectionAB, this.outputA, this.inputB)),
                this.inputA
        );

        assertThat(graph.connections().getFirst().sourceOutputPortId()).isEqualTo(this.outputA);
    }

    @Test
    void validatesExplicitTaskInputPort() {
        final WorkflowGraphValidator.ValidatedGraph graph = this.validate(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA)),
                        this.node(this.nodeB, this.agentB, List.of(this.inputB), List.of(this.outputB))),
                List.of(this.connection(this.connectionAB, this.outputA, this.inputB)),
                this.inputA
        );

        assertThat(graph.taskInputPortId()).isEqualTo(this.inputA);
        assertThat(graph.connections()).containsExactly(this.connection(this.connectionAB, this.outputA, this.inputB));
    }

    @Test
    void validatesExplicitTaskOutputPort() {
        final WorkflowGraphValidator.ValidatedGraph graph = this.validate(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA)),
                        this.node(this.nodeB, this.agentB, List.of(this.inputB), List.of(this.outputB))),
                List.of(this.connection(this.connectionAB, this.outputA, this.inputB)),
                this.inputA,
                this.outputB
        );

        assertThat(graph.taskOutputPortId()).isEqualTo(this.outputB);
    }

    @Test
    void rejectsInvalidTaskInputPort() {
        assertThatThrownBy(() -> this.validator.validateAndNormalize(
                this.projectId,
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA))),
                List.of(),
                null,
                this.outputA,
                List.of(this.agent(this.agentA, this.projectId), this.agent(this.agentB, this.projectId))
        ))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_TASK_INPUT_REQUIRED");

        assertThatThrownBy(() -> this.validate(
                List.of(),
                List.of(),
                UUID.fromString("99999999-9999-4999-8999-999999999999")
        ))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("UNKNOWN_TASK_INPUT_PORT");

        assertThatThrownBy(() -> this.validate(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA))),
                List.of(),
                UUID.fromString("99999999-9999-4999-8999-999999999999")
        ))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("UNKNOWN_TASK_INPUT_PORT");

        assertThatThrownBy(() -> this.validate(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA))),
                List.of(),
                this.outputA
        ))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("INVALID_TASK_INPUT_PORT");
    }

    @Test
    void rejectsInvalidTaskOutputPort() {
        assertThatThrownBy(() -> this.validator.validateAndNormalize(
                this.projectId,
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA))),
                List.of(),
                this.inputA,
                null,
                List.of(this.agent(this.agentA, this.projectId), this.agent(this.agentB, this.projectId))
        ))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("WORKFLOW_TASK_OUTPUT_REQUIRED");

        assertThatThrownBy(() -> this.validate(
                List.of(),
                List.of(),
                null,
                UUID.fromString("99999999-9999-4999-8999-999999999999")
        ))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("UNKNOWN_TASK_OUTPUT_PORT");

        assertThatThrownBy(() -> this.validate(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA))),
                List.of(),
                this.inputA,
                UUID.fromString("99999999-9999-4999-8999-999999999999")
        ))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("UNKNOWN_TASK_OUTPUT_PORT");

        assertThatThrownBy(() -> this.validate(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA))),
                List.of(),
                this.inputA,
                this.inputA
        ))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("INVALID_TASK_OUTPUT_PORT");

        assertThatThrownBy(() -> this.validate(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA)),
                        this.node(this.nodeB, this.agentB, List.of(this.inputB), List.of(this.outputB))),
                List.of(this.connection(this.connectionAB, this.outputA, this.inputB)),
                this.inputA,
                this.outputA
        ))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("TASK_OUTPUT_PORT_NOT_TERMINAL");
    }

    @Test
    void rejectsDuplicateNodeUnknownTargetAndCrossProjectTarget() {
        assertThatThrownBy(() -> this.validate(
                List.of(this.node(this.nodeA, this.agentA, List.of(), List.of()),
                        this.node(this.nodeA, this.agentB, List.of(), List.of())),
                List.of()
        ))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("DUPLICATE_NODE_ID");

        assertThatThrownBy(() -> this.validator.validateAndNormalize(this.projectId, List.of(this.node(this.nodeA, this.agentA, List.of(), List.of())), List.of(), null, List.of()))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("UNKNOWN_NODE_TARGET");

        assertThatThrownBy(() -> this.validator.validateAndNormalize(
                this.projectId,
                List.of(this.node(this.nodeA, this.agentA, List.of(), List.of())),
                List.of(),
                null,
                List.of(this.agent(this.agentA, this.otherProjectId))
        ))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("CROSS_PROJECT_NODE_TARGET");
    }

    private WorkflowGraphValidator.ValidatedGraph validate(final List<Node> nodes, final List<WorkflowConnection> connections) {
        return this.validate(nodes, connections, this.firstInputPortId(nodes));
    }

    private WorkflowGraphValidator.ValidatedGraph validate(final List<Node> nodes, final List<WorkflowConnection> connections, final UUID taskInputPortId) {
        return this.validate(nodes, connections, taskInputPortId, this.firstTerminalOutputPortId(nodes, connections));
    }

    private WorkflowGraphValidator.ValidatedGraph validate(final List<Node> nodes,
                                                           final List<WorkflowConnection> connections,
                                                           final UUID taskInputPortId,
                                                           final UUID taskOutputPortId) {
        return this.validator.validateAndNormalize(
                this.projectId,
                nodes,
                connections,
                taskInputPortId,
                taskOutputPortId,
                List.of(this.agent(this.agentA, this.projectId), this.agent(this.agentB, this.projectId))
        );
    }

    private Node node(final UUID id, final UUID agentId, final List<UUID> inputIds, final List<UUID> outputIds) {
        return new Node(
                id,
                agentId,
                NodeInputMode.DEPENDENCIES_ONLY,
                inputIds.stream().map(inputId -> this.port(inputId, "Input " + inputId, "Input description.", inputIds.indexOf(inputId))).toList(),
                outputIds.stream().map(outputId -> this.port(outputId, "Output " + outputId, "Output description.", outputIds.indexOf(outputId))).toList(),
                new NodePosition(0, 0),
                com.sitionix.forgeagent.domain.model.NodeScopeMode.GLOBAL
        );
    }

    private AgentDefinition agent(final UUID id, final UUID projectId) {
        return new AgentDefinition(id, projectId, "Agent", "agent", "Instructions", OUTPUT_SCHEMA, null, Instant.EPOCH, Instant.EPOCH);
    }

    private NodePort port(final UUID id, final String name, final String description, final int order) {
        return new NodePort(id, name, description, order);
    }

    private WorkflowConnection connection(final UUID id, final UUID sourceOutputPortId, final UUID targetInputPortId) {
        return new WorkflowConnection(id, sourceOutputPortId, targetInputPortId);
    }

    private void expectPortError(final Node node, final String code) {
        assertThatThrownBy(() -> this.validate(List.of(node), List.of()))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo(code);
    }

    private void expectConnectionError(final List<WorkflowConnection> connections, final String code) {
        assertThatThrownBy(() -> this.validate(
                List.of(this.node(this.nodeA, this.agentA, List.of(this.inputA), List.of(this.outputA)),
                        this.node(this.nodeB, this.agentB, List.of(this.inputB, this.inputC), List.of(this.outputB))),
                connections
        ))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo(code);
    }

    private void expectInconsistentGraphError(final List<Node> nodes, final List<WorkflowConnection> connections) {
        assertThatThrownBy(() -> this.validate(nodes, connections, this.inputA))
                .isInstanceOf(ValidationException.class)
                .satisfies(exception -> {
                    final ValidationException validationException = (ValidationException) exception;
                    assertThat(validationException.code()).isEqualTo("INCONSISTENT_WORKFLOW_GRAPH");
                    assertThat(validationException.getMessage()).isEqualTo("Workflow contains nodes that are not reachable from Task Input. Connect all workflow nodes to the execution flow or remove them.");
                });
    }

    private UUID firstInputPortId(final List<Node> nodes) {
        return nodes.stream()
                .flatMap(node -> node.inputs() == null ? java.util.stream.Stream.empty() : node.inputs().stream())
                .map(NodePort::id)
                .findFirst()
                .orElse(null);
    }

    private UUID firstTerminalOutputPortId(final List<Node> nodes, final List<WorkflowConnection> connections) {
        final java.util.Set<UUID> connectedOutputs = connections.stream()
                .map(WorkflowConnection::sourceOutputPortId)
                .collect(java.util.stream.Collectors.toSet());
        return nodes.stream()
                .flatMap(node -> node.outputs() == null ? java.util.stream.Stream.empty() : node.outputs().stream())
                .map(NodePort::id)
                .filter(outputPortId -> !connectedOutputs.contains(outputPortId))
                .findFirst()
                .orElse(null);
    }
}
