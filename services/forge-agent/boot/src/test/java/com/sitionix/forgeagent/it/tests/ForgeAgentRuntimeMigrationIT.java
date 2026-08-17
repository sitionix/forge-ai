package com.sitionix.forgeagent.it.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class ForgeAgentRuntimeMigrationIT {

    private static final UUID PROJECT_ID = UUID.fromString("96000000-0000-4000-8000-000000000001");
    private static final UUID WORKFLOW_ID = UUID.fromString("96000000-0000-4000-8000-000000000002");
    private static final UUID TERMINAL_RUN = UUID.fromString("96000000-0000-4000-8000-000000000003");
    private static final UUID TERMINAL_A = UUID.fromString("96000000-0000-4000-8000-000000000004");
    private static final UUID TERMINAL_B = UUID.fromString("96000000-0000-4000-8000-000000000005");
    private static final UUID ACTIVE_RUN = UUID.fromString("96000000-0000-4000-8000-000000000006");
    private static final UUID ACTIVE_A = UUID.fromString("96000000-0000-4000-8000-000000000007");
    private static final UUID ACTIVE_B = UUID.fromString("96000000-0000-4000-8000-000000000008");
    private static final UUID SOURCE_NODE_A = UUID.fromString("96000000-0000-4000-8000-000000000009");
    private static final UUID SOURCE_NODE_B = UUID.fromString("96000000-0000-4000-8000-000000000010");
    private static final UUID AGENT_ID = UUID.fromString("96000000-0000-4000-8000-000000000011");
    private static final UUID V13_PROJECT_ID = UUID.fromString("97000000-0000-4000-8000-000000000001");
    private static final UUID V13_AGENT_ID = UUID.fromString("97000000-0000-4000-8000-000000000002");
    private static final UUID V13_UNAMBIGUOUS_WORKFLOW = UUID.fromString("97000000-0000-4000-8000-000000000003");
    private static final UUID V13_MULTIPLE_INPUTS_WORKFLOW = UUID.fromString("97000000-0000-4000-8000-000000000004");
    private static final UUID V13_ZERO_INPUT_AMBIGUOUS_WORKFLOW = UUID.fromString("97000000-0000-4000-8000-000000000005");
    private static final UUID V13_UNREACHABLE_ISLAND_WORKFLOW = UUID.fromString("97000000-0000-4000-8000-000000000014");
    private static final UUID V13_UNAMBIGUOUS_NODE = UUID.fromString("97000000-0000-4000-8000-000000000006");
    private static final UUID V13_UNAMBIGUOUS_INPUT = UUID.fromString("97000000-0000-4000-8000-000000000007");
    private static final UUID V13_MULTIPLE_INPUTS_NODE = UUID.fromString("97000000-0000-4000-8000-000000000008");
    private static final UUID V13_MULTIPLE_INPUT_A = UUID.fromString("97000000-0000-4000-8000-000000000009");
    private static final UUID V13_MULTIPLE_INPUT_B = UUID.fromString("97000000-0000-4000-8000-000000000010");
    private static final UUID V13_ZERO_INPUT_NODE = UUID.fromString("97000000-0000-4000-8000-000000000011");
    private static final UUID V13_ZERO_AMBIGUOUS_INPUT_NODE = UUID.fromString("97000000-0000-4000-8000-000000000012");
    private static final UUID V13_ZERO_AMBIGUOUS_INPUT = UUID.fromString("97000000-0000-4000-8000-000000000013");
    private static final UUID V13_UNREACHABLE_NODE_A = UUID.fromString("97000000-0000-4000-8000-000000000015");
    private static final UUID V13_UNREACHABLE_NODE_B = UUID.fromString("97000000-0000-4000-8000-000000000016");
    private static final UUID V13_UNREACHABLE_NODE_C = UUID.fromString("97000000-0000-4000-8000-000000000017");
    private static final UUID V13_UNREACHABLE_NODE_D = UUID.fromString("97000000-0000-4000-8000-000000000018");
    private static final UUID V13_UNREACHABLE_INPUT_A = UUID.fromString("97000000-0000-4000-8000-000000000019");
    private static final UUID V13_UNREACHABLE_INPUT_B = UUID.fromString("97000000-0000-4000-8000-000000000020");
    private static final UUID V13_UNREACHABLE_INPUT_C = UUID.fromString("97000000-0000-4000-8000-000000000021");
    private static final UUID V13_UNREACHABLE_INPUT_D = UUID.fromString("97000000-0000-4000-8000-000000000022");
    private static final UUID V13_UNREACHABLE_OUTPUT_A = UUID.fromString("97000000-0000-4000-8000-000000000023");
    private static final UUID V13_UNREACHABLE_OUTPUT_C = UUID.fromString("97000000-0000-4000-8000-000000000024");
    private static final UUID V13_UNREACHABLE_OUTPUT_D = UUID.fromString("97000000-0000-4000-8000-000000000025");
    private static final UUID V13_UNREACHABLE_CONNECTION_AB = UUID.fromString("97000000-0000-4000-8000-000000000026");
    private static final UUID V13_UNREACHABLE_CONNECTION_CD = UUID.fromString("97000000-0000-4000-8000-000000000027");
    private static final UUID V13_UNREACHABLE_CONNECTION_DC = UUID.fromString("97000000-0000-4000-8000-000000000028");

    @Autowired
    private ForgeAgentTestManager forgeIt;
    @Autowired
    private DataSource dataSource;

    @Test
    void v11ActiveLegacyRunsAreCancelledWhileHistoricalEdgesRemainReadable() {
        final String schema = "runtime_migration_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate jdbc = new JdbcTemplate(this.dataSource);
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            this.flyway(schema, MigrationVersion.fromVersion("11")).migrate();
            this.insertLegacyRows(jdbc, schema);

            this.flyway(schema, null).migrate();

            assertThat(this.count(jdbc, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = 'node_runs'
                      AND column_name = 'depends_on_node_run_ids'
                    """, schema)).isZero();
            assertThat(this.value(jdbc, "SELECT status FROM %s.workflow_runs WHERE id = ?".formatted(schema), ACTIVE_RUN)).isEqualTo("CANCELLED");
            assertThat(this.value(jdbc, "SELECT status FROM %s.node_runs WHERE id = ?".formatted(schema), ACTIVE_B)).isEqualTo("CANCELLED");
            assertThat(this.value(jdbc, "SELECT failure_code FROM %s.node_runs WHERE id = ?".formatted(schema), ACTIVE_B))
                    .isEqualTo("LEGACY_RUNTIME_MIGRATION_CANCELLED");
            assertThat(this.value(jdbc, "SELECT status FROM %s.workflow_runs WHERE id = ?".formatted(schema), TERMINAL_RUN)).isEqualTo("SUCCEEDED");
            assertThat(this.value(jdbc, "SELECT status FROM %s.node_runs WHERE id = ?".formatted(schema), TERMINAL_A)).isEqualTo("SUCCEEDED");
            assertThat(this.value(jdbc, "SELECT status FROM %s.node_runs WHERE id = ?".formatted(schema), TERMINAL_B)).isEqualTo("SUCCEEDED");
            assertThat(this.count(jdbc, """
                    SELECT COUNT(*)
                    FROM %s.workflow_run_execution_edges
                    WHERE workflow_run_id = ?
                      AND source_node_run_id = ?
                      AND target_node_run_id = ?
                      AND source_type = 'LEGACY_DEPENDENCY'
                    """.formatted(schema), TERMINAL_RUN, TERMINAL_A, TERMINAL_B)).isEqualTo(1);
            assertThat(this.count(jdbc, """
                    SELECT COUNT(*)
                    FROM %s.workflow_run_execution_edges
                    WHERE workflow_run_id = ?
                      AND source_node_run_id = ?
                      AND target_node_run_id = ?
                      AND source_type = 'LEGACY_DEPENDENCY'
                    """.formatted(schema), ACTIVE_RUN, ACTIVE_A, ACTIVE_B)).isEqualTo(1);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v13BackfillsOnlyTrulyUnambiguousLegacyTaskInputs() {
        final String schema = "task_input_migration_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate jdbc = new JdbcTemplate(this.dataSource);
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            this.flyway(schema, MigrationVersion.fromVersion("12")).migrate();
            this.insertV13BackfillRows(jdbc, schema);

            this.flyway(schema, null).migrate();

            assertThat(this.uuidValue(jdbc, "SELECT task_input_port_id FROM %s.agent_workflows WHERE id = ?".formatted(schema), V13_UNAMBIGUOUS_WORKFLOW))
                    .isEqualTo(V13_UNAMBIGUOUS_INPUT);
            assertThat(this.uuidValue(jdbc, "SELECT task_input_port_id FROM %s.agent_workflows WHERE id = ?".formatted(schema), V13_MULTIPLE_INPUTS_WORKFLOW))
                    .isNull();
            assertThat(this.uuidValue(jdbc, "SELECT task_input_port_id FROM %s.agent_workflows WHERE id = ?".formatted(schema), V13_ZERO_INPUT_AMBIGUOUS_WORKFLOW))
                    .isNull();
            assertThat(this.uuidValue(jdbc, "SELECT task_input_port_id FROM %s.agent_workflows WHERE id = ?".formatted(schema), V13_UNREACHABLE_ISLAND_WORKFLOW))
                    .isNull();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private Flyway flyway(final String schema, final MigrationVersion target) {
        final var configuration = Flyway.configure()
                .dataSource(this.dataSource)
                .locations("classpath:db/migration")
                .defaultSchema(schema)
                .schemas(schema);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private void insertLegacyRows(final JdbcTemplate jdbc, final String schema) {
        jdbc.update("""
                INSERT INTO %s.agent_projects (id, name, normalized_name, created_at, updated_at)
                VALUES (?, 'Migration Project', 'migration project', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(schema), PROJECT_ID);
        this.insertRun(jdbc, schema, TERMINAL_RUN, "SUCCEEDED");
        this.insertRun(jdbc, schema, ACTIVE_RUN, "RUNNING");
        this.insertNodeRun(jdbc, schema, TERMINAL_A, TERMINAL_RUN, SOURCE_NODE_A, "SUCCEEDED", null);
        this.insertNodeRun(jdbc, schema, TERMINAL_B, TERMINAL_RUN, SOURCE_NODE_B, "SUCCEEDED", TERMINAL_A);
        this.insertNodeRun(jdbc, schema, ACTIVE_A, ACTIVE_RUN, SOURCE_NODE_A, "SUCCEEDED", null);
        this.insertNodeRun(jdbc, schema, ACTIVE_B, ACTIVE_RUN, SOURCE_NODE_B, "PENDING", ACTIVE_A);
    }

    private void insertV13BackfillRows(final JdbcTemplate jdbc, final String schema) {
        jdbc.update("""
                INSERT INTO %s.agent_projects (id, name, normalized_name, created_at, updated_at)
                VALUES (?, 'Task Input Migration Project', 'task input migration project', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(schema), V13_PROJECT_ID);
        jdbc.update("""
                INSERT INTO %s.agent_definitions (
                    id, project_id, name, normalized_name, instructions, output_schema, created_at, updated_at
                )
                VALUES (?, ?, 'Migration Agent', 'migration agent', 'Instructions.', CAST(? AS jsonb), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(schema), V13_AGENT_ID, V13_PROJECT_ID, "{\"type\":\"object\"}");
        this.insertWorkflow(jdbc, schema, V13_UNAMBIGUOUS_WORKFLOW, "Unambiguous Workflow", "unambiguous workflow");
        this.insertWorkflow(jdbc, schema, V13_MULTIPLE_INPUTS_WORKFLOW, "Multiple Inputs Workflow", "multiple inputs workflow");
        this.insertWorkflow(jdbc, schema, V13_ZERO_INPUT_AMBIGUOUS_WORKFLOW, "Zero Input Ambiguous Workflow", "zero input ambiguous workflow");
        this.insertWorkflow(jdbc, schema, V13_UNREACHABLE_ISLAND_WORKFLOW, "Unreachable Island Workflow", "unreachable island workflow");
        this.insertWorkflowNode(jdbc, schema, V13_UNAMBIGUOUS_WORKFLOW, V13_UNAMBIGUOUS_NODE);
        this.insertWorkflowNode(jdbc, schema, V13_MULTIPLE_INPUTS_WORKFLOW, V13_MULTIPLE_INPUTS_NODE);
        this.insertWorkflowNode(jdbc, schema, V13_ZERO_INPUT_AMBIGUOUS_WORKFLOW, V13_ZERO_INPUT_NODE);
        this.insertWorkflowNode(jdbc, schema, V13_ZERO_INPUT_AMBIGUOUS_WORKFLOW, V13_ZERO_AMBIGUOUS_INPUT_NODE);
        this.insertWorkflowNode(jdbc, schema, V13_UNREACHABLE_ISLAND_WORKFLOW, V13_UNREACHABLE_NODE_A);
        this.insertWorkflowNode(jdbc, schema, V13_UNREACHABLE_ISLAND_WORKFLOW, V13_UNREACHABLE_NODE_B);
        this.insertWorkflowNode(jdbc, schema, V13_UNREACHABLE_ISLAND_WORKFLOW, V13_UNREACHABLE_NODE_C);
        this.insertWorkflowNode(jdbc, schema, V13_UNREACHABLE_ISLAND_WORKFLOW, V13_UNREACHABLE_NODE_D);
        this.insertWorkflowInput(jdbc, schema, V13_UNAMBIGUOUS_WORKFLOW, V13_UNAMBIGUOUS_NODE, V13_UNAMBIGUOUS_INPUT, 0);
        this.insertWorkflowInput(jdbc, schema, V13_MULTIPLE_INPUTS_WORKFLOW, V13_MULTIPLE_INPUTS_NODE, V13_MULTIPLE_INPUT_A, 0);
        this.insertWorkflowInput(jdbc, schema, V13_MULTIPLE_INPUTS_WORKFLOW, V13_MULTIPLE_INPUTS_NODE, V13_MULTIPLE_INPUT_B, 1);
        this.insertWorkflowInput(jdbc, schema, V13_ZERO_INPUT_AMBIGUOUS_WORKFLOW, V13_ZERO_AMBIGUOUS_INPUT_NODE, V13_ZERO_AMBIGUOUS_INPUT, 0);
        this.insertWorkflowInput(jdbc, schema, V13_UNREACHABLE_ISLAND_WORKFLOW, V13_UNREACHABLE_NODE_A, V13_UNREACHABLE_INPUT_A, 0);
        this.insertWorkflowInput(jdbc, schema, V13_UNREACHABLE_ISLAND_WORKFLOW, V13_UNREACHABLE_NODE_B, V13_UNREACHABLE_INPUT_B, 0);
        this.insertWorkflowInput(jdbc, schema, V13_UNREACHABLE_ISLAND_WORKFLOW, V13_UNREACHABLE_NODE_C, V13_UNREACHABLE_INPUT_C, 0);
        this.insertWorkflowInput(jdbc, schema, V13_UNREACHABLE_ISLAND_WORKFLOW, V13_UNREACHABLE_NODE_D, V13_UNREACHABLE_INPUT_D, 0);
        this.insertWorkflowOutput(jdbc, schema, V13_UNREACHABLE_ISLAND_WORKFLOW, V13_UNREACHABLE_NODE_A, V13_UNREACHABLE_OUTPUT_A);
        this.insertWorkflowOutput(jdbc, schema, V13_UNREACHABLE_ISLAND_WORKFLOW, V13_UNREACHABLE_NODE_C, V13_UNREACHABLE_OUTPUT_C);
        this.insertWorkflowOutput(jdbc, schema, V13_UNREACHABLE_ISLAND_WORKFLOW, V13_UNREACHABLE_NODE_D, V13_UNREACHABLE_OUTPUT_D);
        this.insertWorkflowConnection(jdbc, schema, V13_UNREACHABLE_CONNECTION_AB, V13_UNREACHABLE_OUTPUT_A, V13_UNREACHABLE_INPUT_B);
        this.insertWorkflowConnection(jdbc, schema, V13_UNREACHABLE_CONNECTION_CD, V13_UNREACHABLE_OUTPUT_C, V13_UNREACHABLE_INPUT_D);
        this.insertWorkflowConnection(jdbc, schema, V13_UNREACHABLE_CONNECTION_DC, V13_UNREACHABLE_OUTPUT_D, V13_UNREACHABLE_INPUT_C);
    }

    private void insertWorkflow(final JdbcTemplate jdbc,
                                final String schema,
                                final UUID workflowId,
                                final String name,
                                final String normalizedName) {
        jdbc.update("""
                INSERT INTO %s.agent_workflows (id, project_id, name, normalized_name, created_at, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(schema), workflowId, V13_PROJECT_ID, name, normalizedName);
    }

    private void insertWorkflowNode(final JdbcTemplate jdbc, final String schema, final UUID workflowId, final UUID nodeId) {
        jdbc.update("""
                INSERT INTO %s.workflow_nodes (
                    id, workflow_id, target_id, position_x, position_y, input_mode
                )
                VALUES (?, ?, ?, 0, 0, 'DEPENDENCIES_ONLY')
                """.formatted(schema), nodeId, workflowId, V13_AGENT_ID);
    }

    private void insertWorkflowInput(final JdbcTemplate jdbc,
                                     final String schema,
                                     final UUID workflowId,
                                     final UUID nodeId,
                                     final UUID portId,
                                     final int order) {
        jdbc.update("""
                INSERT INTO %s.workflow_node_ports (
                    id, workflow_id, node_id, direction, name, description, port_order
                )
                VALUES (?, ?, ?, 'INPUT', ?, 'Input description.', ?)
                """.formatted(schema), portId, workflowId, nodeId, "Input " + order, order);
    }

    private void insertWorkflowOutput(final JdbcTemplate jdbc,
                                      final String schema,
                                      final UUID workflowId,
                                      final UUID nodeId,
                                      final UUID portId) {
        jdbc.update("""
                INSERT INTO %s.workflow_node_ports (
                    id, workflow_id, node_id, direction, name, description, port_order
                )
                VALUES (?, ?, ?, 'OUTPUT', 'Output', 'Output description.', 0)
                """.formatted(schema), portId, workflowId, nodeId);
    }

    private void insertWorkflowConnection(final JdbcTemplate jdbc,
                                          final String schema,
                                          final UUID connectionId,
                                          final UUID sourceOutputPortId,
                                          final UUID targetInputPortId) {
        jdbc.update("""
                INSERT INTO %s.workflow_connections (
                    id, source_output_port_id, target_input_port_id
                )
                VALUES (?, ?, ?)
                """.formatted(schema), connectionId, sourceOutputPortId, targetInputPortId);
    }

    private void insertRun(final JdbcTemplate jdbc, final String schema, final UUID id, final String status) {
        jdbc.update("""
                INSERT INTO %s.workflow_runs (
                    id, project_id, source_workflow_id, workflow_name, input, status, created_at, started_at, finished_at
                )
                VALUES (?, ?, ?, 'Legacy Workflow', 'Legacy input', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CASE WHEN ? IN ('SUCCEEDED', 'FAILED', 'CANCELLED') THEN CURRENT_TIMESTAMP ELSE NULL END)
                """.formatted(schema), id, PROJECT_ID, WORKFLOW_ID, status, status);
    }

    private void insertNodeRun(final JdbcTemplate jdbc,
                               final String schema,
                               final UUID id,
                               final UUID workflowRunId,
                               final UUID sourceNodeId,
                               final String status,
                               final UUID dependency) {
        final String dependencyArray = dependency == null
                ? "ARRAY[]::uuid[]"
                : "ARRAY['%s'::uuid]".formatted(dependency);
        jdbc.update("""
                INSERT INTO %s.node_runs (
                    id, workflow_run_id, source_node_id, source_agent_id, agent_name, agent_instructions,
                    agent_output_schema, depends_on_node_run_ids, position_x, position_y, status, output,
                    input_mode, execution_model_provider_id, execution_model_id, execution_model_effort_id,
                    created_at, started_at, finished_at
                )
                VALUES (?, ?, ?, ?, 'Legacy Agent', 'Legacy instructions.', CAST(? AS jsonb),
                        %s,
                        0, 0, ?, CAST(? AS jsonb), 'DEPENDENCIES_ONLY', 'codex', 'discovered-model', 'medium',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CASE WHEN ? IN ('SUCCEEDED', 'FAILED', 'CANCELLED') THEN CURRENT_TIMESTAMP ELSE NULL END)
                """.formatted(schema, dependencyArray),
                id,
                workflowRunId,
                sourceNodeId,
                AGENT_ID,
                "{\"type\":\"object\"}",
                status,
                status.equals("SUCCEEDED") ? "{\"ok\":true}" : null,
                status);
    }

    private String value(final JdbcTemplate jdbc, final String sql, final UUID id) {
        return jdbc.queryForObject(sql, String.class, id);
    }

    private UUID uuidValue(final JdbcTemplate jdbc, final String sql, final UUID id) {
        return jdbc.queryForObject(sql, UUID.class, id);
    }

    private int count(final JdbcTemplate jdbc, final String sql, final Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
