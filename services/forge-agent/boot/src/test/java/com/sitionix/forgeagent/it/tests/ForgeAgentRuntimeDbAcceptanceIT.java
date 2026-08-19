package com.sitionix.forgeagent.it.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class ForgeAgentRuntimeDbAcceptanceIT {

    @Autowired
    private ForgeAgentTestManager forgeIt;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void workflowRunRepositorySnapshotPreservesOrdinalOrder() {
        final RuntimeRows rows = this.insertRuntimeRows();
        final UUID repoA = UUID.randomUUID();
        final UUID repoB = UUID.randomUUID();

        this.jdbc.update("""
                INSERT INTO workflow_run_repositories (workflow_run_id, repository_id, repository_ordinal)
                VALUES (?, ?, ?), (?, ?, ?)
                """, rows.runId(), repoB, 0, rows.runId(), repoA, 1);

        assertThat(this.jdbc.queryForList("""
                SELECT repository_id
                FROM workflow_run_repositories
                WHERE workflow_run_id = ?
                ORDER BY repository_ordinal
                """, UUID.class, rows.runId())).containsExactly(repoB, repoA);
    }

    @Test
    void nodeRunGlobalUniquenessRejectsDuplicateLogicalRunInFrame() {
        final RuntimeRows rows = this.insertRuntimeRows();
        this.insertNodeRun(rows, UUID.randomUUID(), rows.nodeA(), null);

        assertThatThrownBy(() -> this.insertNodeRun(rows, UUID.randomUUID(), rows.nodeA(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nodeRunRepositoryUniquenessAllowsDifferentRepositoriesAndRejectsDuplicateRepository() {
        final RuntimeRows rows = this.insertRuntimeRows();
        final UUID repoA = UUID.randomUUID();
        final UUID repoB = UUID.randomUUID();
        this.insertNodeRun(rows, UUID.randomUUID(), rows.nodeA(), repoA);
        this.insertNodeRun(rows, UUID.randomUUID(), rows.nodeA(), repoB);

        assertThatThrownBy(() -> this.insertNodeRun(rows, UUID.randomUUID(), rows.nodeA(), repoA))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void inputActivationGlobalUniquenessRejectsDuplicateFramePort() {
        final RuntimeRows rows = this.insertRuntimeRows();
        final UUID inputPort = UUID.randomUUID();
        this.insertInputActivation(rows, inputPort, null);

        assertThatThrownBy(() -> this.insertInputActivation(rows, inputPort, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void inputActivationRepositoryUniquenessAllowsDifferentRepositoriesAndRejectsDuplicateRepository() {
        final RuntimeRows rows = this.insertRuntimeRows();
        final UUID inputPort = UUID.randomUUID();
        final UUID repoA = UUID.randomUUID();
        final UUID repoB = UUID.randomUUID();
        this.insertInputActivation(rows, inputPort, repoA);
        this.insertInputActivation(rows, inputPort, repoB);

        assertThatThrownBy(() -> this.insertInputActivation(rows, inputPort, repoA))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void connectionResolutionScopedBroadcastAllowsDifferentTargetsAndRejectsTargetCollisions() {
        final RuntimeRows rows = this.insertRuntimeRows();
        final UUID sourceRun = UUID.randomUUID();
        final UUID connection = UUID.randomUUID();
        final UUID targetInput = UUID.randomUUID();
        final UUID repoA = UUID.randomUUID();
        final UUID repoB = UUID.randomUUID();
        this.insertNodeRun(rows, sourceRun, rows.nodeA(), null);

        this.insertConnectionResolution(rows, sourceRun, connection, targetInput, repoA);
        this.insertConnectionResolution(rows, sourceRun, connection, targetInput, repoB);

        assertThatThrownBy(() -> this.insertConnectionResolution(rows, sourceRun, connection, targetInput, repoA))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void connectionResolutionGlobalTargetUniquenessRejectsDuplicateGlobalResolution() {
        final RuntimeRows rows = this.insertRuntimeRows();
        final UUID sourceRun = UUID.randomUUID();
        final UUID connection = UUID.randomUUID();
        final UUID targetInput = UUID.randomUUID();
        this.insertNodeRun(rows, sourceRun, rows.nodeA(), null);
        this.insertConnectionResolution(rows, sourceRun, connection, targetInput, null);

        assertThatThrownBy(() -> this.insertConnectionResolution(rows, sourceRun, connection, targetInput, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private RuntimeRows insertRuntimeRows() {
        final UUID projectId = UUID.randomUUID();
        final UUID agentId = UUID.randomUUID();
        final UUID workflowId = UUID.randomUUID();
        final UUID runId = UUID.randomUUID();
        final UUID frameId = UUID.randomUUID();
        final UUID nodeA = UUID.randomUUID();
        final UUID nodeB = UUID.randomUUID();
        this.jdbc.update("""
                INSERT INTO agent_projects (id, name, normalized_name, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, projectId, "Project " + projectId, "project-" + projectId);
        this.jdbc.update("""
                INSERT INTO agent_definitions (
                    id, project_id, name, normalized_name, instructions, output_schema, created_at, updated_at,
                    model_provider_id, model_id, model_effort_id
                )
                VALUES (?, ?, 'Agent', ?, 'Do work.', CAST(? AS jsonb), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        'codex', 'discovered-model', 'medium')
                """, agentId, projectId, "agent-" + agentId, "{\"type\":\"object\"}");
        this.jdbc.update("""
                INSERT INTO agent_workflows (id, project_id, name, normalized_name, created_at, updated_at)
                VALUES (?, ?, 'Workflow', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, workflowId, projectId, "workflow-" + workflowId);
        this.jdbc.update("""
                INSERT INTO workflow_runs (id, project_id, source_workflow_id, workflow_name, input, status, created_at)
                VALUES (?, ?, ?, 'Workflow', 'Input.', 'RUNNING', CURRENT_TIMESTAMP)
                """, runId, projectId, workflowId);
        this.jdbc.update("""
                INSERT INTO workflow_execution_frames (id, workflow_run_id, parent_frame_id, created_at)
                VALUES (?, ?, NULL, CURRENT_TIMESTAMP)
                """, frameId, runId);
        return new RuntimeRows(agentId, runId, frameId, nodeA);
    }

    private void insertNodeRun(final RuntimeRows rows, final UUID nodeRunId, final UUID sourceNodeId,
                               final UUID repositoryId) {
        this.jdbc.update("""
                INSERT INTO node_runs (
                    id, workflow_run_id, source_node_id, source_agent_id, agent_name, agent_instructions,
                    agent_output_schema, input_mode, position_x, position_y, status,
                    execution_model_provider_id, execution_model_id, execution_model_effort_id,
                    execution_frame_id, created_at, repository_id
                )
                VALUES (?, ?, ?, ?, 'Agent', 'Do work.', CAST(? AS jsonb), 'DEPENDENCIES_ONLY',
                        0, 0, 'PENDING', 'codex', 'discovered-model', 'medium', ?, CURRENT_TIMESTAMP, ?)
                """, nodeRunId, rows.runId(), sourceNodeId, rows.agentId(), "{\"type\":\"object\"}",
                rows.frameId(), repositoryId);
    }

    private void insertInputActivation(final RuntimeRows rows, final UUID targetInputPortId, final UUID repositoryId) {
        this.jdbc.update("""
                INSERT INTO workflow_input_activation_resolutions (
                    id, workflow_run_id, activation_frame_id, target_input_port_id, repository_id, activated_node_run_id, created_at
                )
                VALUES (?, ?, ?, ?, ?, NULL, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), rows.runId(), rows.frameId(), targetInputPortId, repositoryId);
    }

    private void insertConnectionResolution(final RuntimeRows rows, final UUID sourceNodeRunId, final UUID connectionId,
                                            final UUID targetInputPortId, final UUID targetRepositoryId) {
        this.jdbc.update("""
                INSERT INTO workflow_connection_resolutions (
                    id, workflow_run_id, execution_frame_id, source_node_run_id, source_connection_id,
                    target_input_port_id, target_repository_id, resolution_type, payload, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 'DELIVERED', CAST(? AS jsonb), CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), rows.runId(), rows.frameId(), sourceNodeRunId, connectionId,
                targetInputPortId, targetRepositoryId, "{\"done\":true}");
    }

    private record RuntimeRows(UUID agentId, UUID runId, UUID frameId, UUID nodeA) {
    }
}
