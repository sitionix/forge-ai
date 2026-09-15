package com.sitionix.forgeagent.it.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
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
    private static final UUID V15_LEGACY_RUN = UUID.fromString("98000000-0000-4000-8000-000000000001");

    @Autowired
    private ForgeAgentTestManager forgeIt;
    @Autowired
    private DataSource dataSource;

    @Test
    void v26CreatesFencedAgentSessionAndTurnContract() {
        final String schema = "agent_sessions_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate jdbc = new JdbcTemplate(this.dataSource);
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            this.flyway(schema, null).migrate();
            assertThat(this.count(jdbc, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name IN ('agent_execution_sessions','agent_execution_turns')", schema)).isEqualTo(2);
            assertThat(this.count(jdbc, "SELECT COUNT(*) FROM pg_indexes WHERE schemaname=? AND indexname IN ('uq_agent_sessions_reusable_global','uq_agent_sessions_reusable_scope','uq_agent_turns_active_writer')", schema)).isEqualTo(3);
            assertThat(this.count(jdbc, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name='agent_execution_sessions' AND column_name IN ('lease_owner_id','lease_token','lease_expires_at')", schema)).isEqualTo(3);
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void v29AddsNullableProviderRecoveryEvidenceAndFencingToHistoricalTurns() {
        final String schema = "agent_turn_recovery_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate jdbc = new JdbcTemplate(this.dataSource);
        final UUID workflowRunId = UUID.randomUUID();
        final UUID sourceNodeId = UUID.randomUUID();
        final UUID nodeRunId = UUID.randomUUID();
        final UUID sessionId = UUID.randomUUID();
        final UUID turnId = UUID.randomUUID();
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            this.flyway(schema, MigrationVersion.fromVersion("28")).migrate();
            this.insertHistoricalTrackedTurn(jdbc, schema, workflowRunId, sourceNodeId, nodeRunId, sessionId, turnId);

            this.flyway(schema, null).migrate();

            final var recovery = jdbc.queryForMap("""
                    SELECT provider_recovery_state, provider_recovery_terminal_outcome, provider_recovery_checked_at,
                           recovery_lease_owner_id, recovery_lease_expires_at, recovery_lease_token
                    FROM %s.agent_execution_turns WHERE id = ?
                    """.formatted(schema), turnId);
            assertThat(recovery.get("provider_recovery_state")).isNull();
            assertThat(recovery.get("provider_recovery_terminal_outcome")).isNull();
            assertThat(recovery.get("provider_recovery_checked_at")).isNull();
            assertThat(recovery.get("recovery_lease_owner_id")).isNull();
            assertThat(recovery.get("recovery_lease_expires_at")).isNull();
            assertThat(recovery.get("recovery_lease_token")).isEqualTo(0L);

            assertThatThrownBy(() -> jdbc.update("UPDATE %s.agent_execution_turns SET provider_recovery_state='INVALID' WHERE id=?".formatted(schema), turnId))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE %s.agent_execution_turns SET provider_recovery_terminal_outcome='INVALID' WHERE id=?".formatted(schema), turnId))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE %s.agent_execution_turns SET recovery_lease_token=-1 WHERE id=?".formatted(schema), turnId))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v30AddsNullableUniqueRetryLineageAndRejectsSelfReference() {
        final String schema = "node_run_retry_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate jdbc = new JdbcTemplate(this.dataSource);
        final UUID workflowRunId = UUID.randomUUID();
        final UUID sourceNodeId = UUID.randomUUID();
        final UUID parentId = UUID.randomUUID();
        final UUID sessionId = UUID.randomUUID();
        final UUID turnId = UUID.randomUUID();
        final UUID firstChildId = UUID.randomUUID();
        final UUID secondChildId = UUID.randomUUID();
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            this.flyway(schema, MigrationVersion.fromVersion("29")).migrate();
            this.insertHistoricalTrackedTurn(jdbc, schema, workflowRunId, sourceNodeId, parentId, sessionId, turnId);

            this.flyway(schema, null).migrate();

            assertThat(this.uuidValue(jdbc,
                    "SELECT retry_of_node_run_id FROM %s.node_runs WHERE id=?".formatted(schema), parentId)).isNull();
            jdbc.update("INSERT INTO %1$s.node_runs (id,workflow_run_id,source_node_id,source_agent_id,agent_name,agent_instructions,agent_output_schema,position_x,position_y,status,output,failure_code,failure_message,created_at,started_at,finished_at,execution_model_provider_id,execution_model_id,execution_model_effort_id,input_mode,execution_frame_id,entered_via_input_port_id,activation_frame_id,selected_output_port_id,routing_completed_at,repository_id,context_mode,context_tracking_version,retry_of_node_run_id) SELECT ?,workflow_run_id,source_node_id,source_agent_id,agent_name,agent_instructions,agent_output_schema,position_x,position_y,status,output,failure_code,failure_message,created_at,started_at,finished_at,execution_model_provider_id,execution_model_id,execution_model_effort_id,input_mode,execution_frame_id,entered_via_input_port_id,activation_frame_id,selected_output_port_id,routing_completed_at,repository_id,context_mode,context_tracking_version,? FROM %1$s.node_runs WHERE id=?".formatted(schema),
                    firstChildId, parentId, parentId);
            assertThat(this.uuidValue(jdbc,
                    "SELECT retry_of_node_run_id FROM %s.node_runs WHERE id=?".formatted(schema), firstChildId)).isEqualTo(parentId);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO %1$s.node_runs (id,workflow_run_id,source_node_id,source_agent_id,agent_name,agent_instructions,agent_output_schema,position_x,position_y,status,output,failure_code,failure_message,created_at,started_at,finished_at,execution_model_provider_id,execution_model_id,execution_model_effort_id,input_mode,execution_frame_id,entered_via_input_port_id,activation_frame_id,selected_output_port_id,routing_completed_at,repository_id,context_mode,context_tracking_version,retry_of_node_run_id) SELECT ?,workflow_run_id,source_node_id,source_agent_id,agent_name,agent_instructions,agent_output_schema,position_x,position_y,status,output,failure_code,failure_message,created_at,started_at,finished_at,execution_model_provider_id,execution_model_id,execution_model_effort_id,input_mode,execution_frame_id,entered_via_input_port_id,activation_frame_id,selected_output_port_id,routing_completed_at,repository_id,context_mode,context_tracking_version,? FROM %1$s.node_runs WHERE id=?".formatted(schema),
                    secondChildId, parentId, parentId)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE %s.node_runs SET retry_of_node_run_id=id WHERE id=?".formatted(schema), parentId))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void v31RetiresGlobalContextWithoutChangingHistoricalRowsOrProviderUniqueness() {
        this.assertContextResetMigration(null, "uq_agent_sessions_reusable_global");
    }

    @Test
    void v31RetiresRepositoryContextWithoutChangingOtherRepositoryReuse() {
        this.assertContextResetMigration(UUID.randomUUID(), "uq_agent_sessions_reusable_scope");
    }

    private void assertContextResetMigration(final UUID repositoryId, final String reusableIndex) {
        final String schema = "context_reset_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate jdbc = new JdbcTemplate(this.dataSource);
        final UUID workflowRunId = UUID.randomUUID();
        final UUID sourceNodeId = UUID.randomUUID();
        final UUID nodeRunId = UUID.randomUUID();
        final UUID sessionId = UUID.randomUUID();
        final UUID turnId = UUID.randomUUID();
        final UUID replacementId = UUID.randomUUID();
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            this.flyway(schema, MigrationVersion.fromVersion("30")).migrate();
            this.insertHistoricalTrackedTurn(jdbc, schema, workflowRunId, sourceNodeId, nodeRunId, sessionId, turnId);
            jdbc.update("UPDATE %s.workflow_run_nodes SET context_mode='REUSE_WITHIN_WORKFLOW_NODE', scope_mode=? WHERE workflow_run_id=? AND source_node_id=?".formatted(schema),
                    repositoryId == null ? "GLOBAL" : "PER_SCOPE", workflowRunId, sourceNodeId);
            if (repositoryId != null) {
                jdbc.update("INSERT INTO %s.workflow_run_repositories (workflow_run_id, repository_id, repository_ordinal) VALUES (?, ?, 0)".formatted(schema),
                        workflowRunId, repositoryId);
            }
            jdbc.update("UPDATE %s.node_runs SET context_mode='REUSE_WITHIN_WORKFLOW_NODE', repository_id=? WHERE id=?".formatted(schema), repositoryId, nodeRunId);
            jdbc.update("UPDATE %s.agent_execution_turns SET status='SUCCEEDED' WHERE id=?".formatted(schema), turnId);
            jdbc.update("UPDATE %s.agent_execution_sessions SET context_mode='REUSE_WITHIN_WORKFLOW_NODE', status='IDLE', repository_id=?, provider_conversation_id='historical-conversation' WHERE id=?".formatted(schema),
                    repositoryId, sessionId);
            final var historicalSession = jdbc.queryForMap("SELECT * FROM %s.agent_execution_sessions WHERE id=?".formatted(schema), sessionId);
            final var historicalTurn = jdbc.queryForMap("SELECT * FROM %s.agent_execution_turns WHERE id=?".formatted(schema), turnId);
            final String providerIndex = this.value(jdbc,
                    "SELECT indexdef FROM pg_indexes WHERE schemaname=? AND indexname='uq_agent_sessions_provider_conversation'", schema);

            this.flyway(schema, null).migrate();

            final var migratedSession = jdbc.queryForMap("SELECT * FROM %s.agent_execution_sessions WHERE id=?".formatted(schema), sessionId);
            assertThat(migratedSession).containsAllEntriesOf(historicalSession).containsEntry("context_reset_at", null);
            assertThat(this.value(jdbc,
                    "SELECT data_type FROM information_schema.columns WHERE table_schema=? AND table_name='agent_execution_sessions' AND column_name='context_reset_at'", schema))
                    .isEqualTo("timestamp with time zone");
            assertThat(this.value(jdbc,
                    "SELECT indexdef FROM pg_indexes WHERE schemaname=? AND indexname='uq_agent_sessions_provider_conversation'", schema))
                    .isEqualTo(providerIndex);
            assertThatThrownBy(() -> this.insertReusableSessionCopy(jdbc, schema, sessionId, replacementId, repositoryId))
                    .isInstanceOf(DuplicateKeyException.class).hasMessageContaining(reusableIndex);

            UUID otherRepositorySessionId = null;
            if (repositoryId != null) {
                final UUID otherRepositoryId = UUID.randomUUID();
                otherRepositorySessionId = UUID.randomUUID();
                jdbc.update("INSERT INTO %s.workflow_run_repositories (workflow_run_id, repository_id, repository_ordinal) VALUES (?, ?, 1)".formatted(schema),
                        workflowRunId, otherRepositoryId);
                this.insertReusableSessionCopy(jdbc, schema, sessionId, otherRepositorySessionId, otherRepositoryId);
            }

            jdbc.update("UPDATE %s.agent_execution_sessions SET context_reset_at=CURRENT_TIMESTAMP WHERE id=?".formatted(schema), sessionId);
            this.insertReusableSessionCopy(jdbc, schema, sessionId, replacementId, repositoryId);

            final var retiredSession = jdbc.queryForMap("SELECT * FROM %s.agent_execution_sessions WHERE id=?".formatted(schema), sessionId);
            assertThat(retiredSession).containsAllEntriesOf(historicalSession);
            assertThat(retiredSession.get("context_reset_at")).isNotNull();
            assertThat(jdbc.queryForMap("SELECT * FROM %s.agent_execution_turns WHERE id=?".formatted(schema), turnId)).isEqualTo(historicalTurn);
            assertThat(jdbc.queryForMap("SELECT context_reset_at, provider_conversation_id FROM %s.agent_execution_sessions WHERE id=?".formatted(schema), replacementId))
                    .containsEntry("context_reset_at", null).containsEntry("provider_conversation_id", null);
            assertThatThrownBy(() -> this.insertReusableSessionCopy(jdbc, schema, sessionId, UUID.randomUUID(), repositoryId))
                    .isInstanceOf(DuplicateKeyException.class).hasMessageContaining(reusableIndex);
            assertThatThrownBy(() -> jdbc.update("UPDATE %s.agent_execution_sessions SET provider_conversation_id='historical-conversation' WHERE id=?".formatted(schema), replacementId))
                    .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_agent_sessions_provider_conversation");
            if (otherRepositorySessionId != null) {
                assertThat(jdbc.queryForMap("SELECT context_reset_at, status FROM %s.agent_execution_sessions WHERE id=?".formatted(schema), otherRepositorySessionId))
                        .containsEntry("context_reset_at", null).containsEntry("status", "IDLE");
            }
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private void insertReusableSessionCopy(final JdbcTemplate jdbc, final String schema, final UUID sourceId,
                                          final UUID sessionId, final UUID repositoryId) {
        jdbc.update("""
                INSERT INTO %1$s.agent_execution_sessions (
                    id, workflow_run_id, source_node_id, source_agent_id, repository_id,
                    provider_id, context_mode, status, created_at, updated_at
                ) SELECT ?, workflow_run_id, source_node_id, source_agent_id, ?,
                    provider_id, context_mode, status, created_at, updated_at
                  FROM %1$s.agent_execution_sessions WHERE id=?
                """.formatted(schema), sessionId, repositoryId, sourceId);
    }

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
    void v14ClearsPreexistingUnreachableTaskInput() {
        final String schema = "task_input_migration_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate jdbc = new JdbcTemplate(this.dataSource);
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            this.flyway(schema, MigrationVersion.fromVersion("12")).migrate();
            this.insertV13BackfillRows(jdbc, schema);

            this.flyway(schema, MigrationVersion.fromVersion("13")).migrate();

            assertThat(this.uuidValue(jdbc, "SELECT task_input_port_id FROM %s.agent_workflows WHERE id = ?".formatted(schema), V13_UNAMBIGUOUS_WORKFLOW))
                    .isEqualTo(V13_UNAMBIGUOUS_INPUT);
            assertThat(this.uuidValue(jdbc, "SELECT task_input_port_id FROM %s.agent_workflows WHERE id = ?".formatted(schema), V13_MULTIPLE_INPUTS_WORKFLOW))
                    .isNull();
            assertThat(this.uuidValue(jdbc, "SELECT task_input_port_id FROM %s.agent_workflows WHERE id = ?".formatted(schema), V13_ZERO_INPUT_AMBIGUOUS_WORKFLOW))
                    .isNull();
            assertThat(this.uuidValue(jdbc, "SELECT task_input_port_id FROM %s.agent_workflows WHERE id = ?".formatted(schema), V13_UNREACHABLE_ISLAND_WORKFLOW))
                    .isNull();

            jdbc.update("UPDATE %s.agent_workflows SET task_input_port_id = ? WHERE id = ?".formatted(schema),
                    V13_UNREACHABLE_INPUT_A,
                    V13_UNREACHABLE_ISLAND_WORKFLOW);

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

    @Test
    void v15AddsNullableTaskOutputAndResultFieldsWithoutBackfillOrCancellingLegacyRuns() {
        final String schema = "task_output_migration_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcTemplate jdbc = new JdbcTemplate(this.dataSource);
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            this.flyway(schema, MigrationVersion.fromVersion("12")).migrate();
            this.insertV13BackfillRows(jdbc, schema);
            this.flyway(schema, MigrationVersion.fromVersion("14")).migrate();
            jdbc.update("""
                    INSERT INTO %s.workflow_runs (
                        id, project_id, source_workflow_id, workflow_name, input, status, created_at, started_at
                    )
                    VALUES (?, ?, ?, 'Legacy active', 'Legacy input', 'RUNNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.formatted(schema), V15_LEGACY_RUN, V13_PROJECT_ID, V13_UNAMBIGUOUS_WORKFLOW);

            this.flyway(schema, null).migrate();

            assertThat(this.count(jdbc, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = 'agent_workflows'
                      AND column_name = 'task_output_port_id'
                    """, schema)).isEqualTo(1);
            assertThat(this.count(jdbc, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = 'workflow_runs'
                      AND column_name IN ('task_output_port_id', 'result', 'result_source_node_run_id')
                    """, schema)).isEqualTo(3);
            assertThat(this.uuidValue(jdbc, "SELECT task_output_port_id FROM %s.agent_workflows WHERE id = ?".formatted(schema), V13_UNAMBIGUOUS_WORKFLOW))
                    .isNull();
            assertThat(this.uuidValue(jdbc, "SELECT task_output_port_id FROM %s.workflow_runs WHERE id = ?".formatted(schema), V15_LEGACY_RUN))
                    .isNull();
            assertThat(this.value(jdbc, "SELECT result FROM %s.workflow_runs WHERE id = ?".formatted(schema), V15_LEGACY_RUN))
                    .isNull();
            assertThat(this.uuidValue(jdbc, "SELECT result_source_node_run_id FROM %s.workflow_runs WHERE id = ?".formatted(schema), V15_LEGACY_RUN))
                    .isNull();
            assertThat(this.value(jdbc, "SELECT status FROM %s.workflow_runs WHERE id = ?".formatted(schema), V15_LEGACY_RUN))
                    .isEqualTo("RUNNING");
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

    private void insertHistoricalTrackedTurn(final JdbcTemplate jdbc, final String schema,
                                             final UUID workflowRunId, final UUID sourceNodeId,
                                             final UUID nodeRunId, final UUID sessionId, final UUID turnId) {
        final UUID projectId = UUID.randomUUID();
        final UUID agentId = UUID.randomUUID();
        final UUID executionFrameId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO %s.agent_projects (id, name, normalized_name, created_at, updated_at)
                VALUES (?, 'Recovery migration project', 'recovery migration project', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(schema), projectId);
        jdbc.update("""
                INSERT INTO %s.workflow_runs (id, project_id, source_workflow_id, workflow_name, input, status, created_at)
                VALUES (?, ?, ?, 'Recovery migration workflow', 'input', 'QUEUED', CURRENT_TIMESTAMP)
                """.formatted(schema), workflowRunId, projectId, UUID.randomUUID());
        jdbc.update("""
                INSERT INTO %s.workflow_run_nodes (
                    workflow_run_id, source_node_id, source_agent_id, agent_name, agent_instructions,
                    agent_output_schema, execution_model_provider_id, execution_model_id, input_mode,
                    position_x, position_y, scope_mode, context_mode
                ) VALUES (?, ?, ?, 'Recovery migration agent', 'Instructions.', CAST(? AS jsonb),
                    'codex', 'gpt-5', 'DEPENDENCIES_ONLY', 0, 0, 'GLOBAL', 'FRESH_EACH_NODE_RUN')
                """.formatted(schema), workflowRunId, sourceNodeId, agentId, "{\"type\":\"object\"}");
        jdbc.update("""
                INSERT INTO %s.workflow_execution_frames (id, workflow_run_id, created_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                """.formatted(schema), executionFrameId, workflowRunId);
        jdbc.update("""
                INSERT INTO %s.node_runs (
                    id, workflow_run_id, source_node_id, source_agent_id, agent_name, agent_instructions,
                    agent_output_schema, position_x, position_y, status, created_at, execution_frame_id,
                    execution_model_provider_id, execution_model_id, input_mode, context_mode
                ) VALUES (?, ?, ?, ?, 'Recovery migration agent', 'Instructions.', CAST(? AS jsonb),
                    0, 0, 'PENDING', CURRENT_TIMESTAMP, ?, 'codex', 'gpt-5', 'DEPENDENCIES_ONLY', 'FRESH_EACH_NODE_RUN')
                """.formatted(schema), nodeRunId, workflowRunId, sourceNodeId, agentId,
                "{\"type\":\"object\"}", executionFrameId);
        jdbc.update("""
                INSERT INTO %s.agent_execution_sessions (
                    id, workflow_run_id, source_node_id, source_agent_id, provider_id, context_mode, status,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'codex', 'FRESH_EACH_NODE_RUN', 'WAITING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(schema), sessionId, workflowRunId, sourceNodeId, agentId);
        jdbc.update("""
                INSERT INTO %s.agent_execution_turns (
                    id, agent_session_id, node_run_id, sequence, status, created_at, updated_at
                ) VALUES (?, ?, ?, 1, 'QUEUED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(schema), turnId, sessionId, nodeRunId);
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

    private String value(final JdbcTemplate jdbc, final String sql, final Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private UUID uuidValue(final JdbcTemplate jdbc, final String sql, final UUID id) {
        return jdbc.queryForObject(sql, UUID.class, id);
    }

    private int count(final JdbcTemplate jdbc, final String sql, final Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
