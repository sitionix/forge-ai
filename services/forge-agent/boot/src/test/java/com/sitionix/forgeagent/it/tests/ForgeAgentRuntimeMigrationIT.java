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

    private int count(final JdbcTemplate jdbc, final String sql, final Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
