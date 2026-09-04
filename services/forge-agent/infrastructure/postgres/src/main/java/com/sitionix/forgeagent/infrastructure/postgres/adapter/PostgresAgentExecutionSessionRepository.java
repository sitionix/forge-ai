package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class PostgresAgentExecutionSessionRepository implements AgentExecutionSessionRepository {
    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public AgentExecutionAllocation allocate(final NodeRun nodeRun, final String providerId) {
        final AgentExecutionSession session = nodeRun.contextMode() == NodeContextMode.FRESH_EACH_NODE_RUN
                ? this.createSession(nodeRun, providerId)
                : this.findOrCreateReusable(nodeRun, providerId);
        final int sequence = nodeRun.contextMode() == NodeContextMode.FRESH_EACH_NODE_RUN ? 1
                : this.jdbc.queryForObject("SELECT COALESCE(MAX(sequence), 0) + 1 FROM agent_execution_turns WHERE agent_session_id = ?", Integer.class, session.id());
        final UUID turnId = UUID.randomUUID();
        final Instant now = Instant.now();
        this.jdbc.update("INSERT INTO agent_execution_turns(id,agent_session_id,node_run_id,sequence,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                turnId, session.id(), nodeRun.id(), sequence, AgentExecutionTurnStatus.QUEUED.name(), now, now);
        return this.findByNodeRunId(nodeRun.id()).orElseThrow();
    }

    private AgentExecutionSession findOrCreateReusable(final NodeRun nodeRun, final String providerId) {
        this.jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                nodeRun.workflowRunId() + ":" + nodeRun.sourceNodeId() + ":" + nodeRun.repositoryId());
        final List<AgentExecutionSession> existing = this.findReusable(nodeRun);
        if (!existing.isEmpty()) return existing.getFirst();
        return this.createSession(nodeRun, providerId);
    }

    private List<AgentExecutionSession> findReusable(final NodeRun nodeRun) {
        return this.jdbc.query("SELECT * FROM agent_execution_sessions WHERE workflow_run_id=? AND source_node_id=? AND context_mode='REUSE_WITHIN_WORKFLOW_NODE' AND repository_id IS NOT DISTINCT FROM ? FOR UPDATE",
                this::session, nodeRun.workflowRunId(), nodeRun.sourceNodeId(), nodeRun.repositoryId());
    }

    private AgentExecutionSession createSession(final NodeRun nodeRun, final String providerId) {
        if (providerId == null || providerId.isBlank()) throw new ConflictException("AGENT_CONTEXT_PERSISTENCE_FAILED", "Execution provider is required for an agent context.");
        final UUID id = UUID.randomUUID();
        final Instant now = Instant.now();
        this.jdbc.update("INSERT INTO agent_execution_sessions(id,workflow_run_id,source_node_id,source_agent_id,repository_id,provider_id,context_mode,status,lease_token,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,0,?,?)",
                id, nodeRun.workflowRunId(), nodeRun.sourceNodeId(), nodeRun.sourceAgentId(), nodeRun.repositoryId(), providerId,
                nodeRun.contextMode().name(), AgentExecutionSessionStatus.WAITING.name(), now, now);
        return this.jdbc.queryForObject("SELECT * FROM agent_execution_sessions WHERE id=?", this::session, id);
    }

    @Override
    public Optional<AgentExecutionAllocation> findByNodeRunId(final UUID nodeRunId) {
        final List<AgentExecutionAllocation> rows = this.jdbc.query("SELECT s.*,t.id turn_id,t.node_run_id,t.provider_turn_id,t.sequence turn_sequence,t.status turn_status,t.failure_code turn_failure_code,t.failure_message turn_failure_message,t.started_at turn_started_at,t.finished_at turn_finished_at,t.created_at turn_created_at,t.updated_at turn_updated_at FROM agent_execution_turns t JOIN agent_execution_sessions s ON s.id=t.agent_session_id WHERE t.node_run_id=?",
                (rs, row) -> new AgentExecutionAllocation(this.session(rs, row), this.turn(rs)), nodeRunId);
        return rows.stream().findFirst();
    }

    @Override
    public List<AgentExecutionAllocation> findByWorkflowRunId(final UUID workflowRunId) {
        return this.jdbc.query("SELECT s.*,t.id turn_id,t.node_run_id,t.provider_turn_id,t.sequence turn_sequence,t.status turn_status,t.failure_code turn_failure_code,t.failure_message turn_failure_message,t.started_at turn_started_at,t.finished_at turn_finished_at,t.created_at turn_created_at,t.updated_at turn_updated_at FROM agent_execution_turns t JOIN agent_execution_sessions s ON s.id=t.agent_session_id WHERE s.workflow_run_id=? ORDER BY s.created_at,t.sequence",
                (rs,row) -> new AgentExecutionAllocation(this.session(rs,row), this.turn(rs)), workflowRunId);
    }

    @Override
    @Transactional
    public Optional<AgentSessionExecutionClaim> acquire(final UUID nodeRunId, final String ownerId) {
        final List<AgentExecutionAllocation> target = this.jdbc.query("SELECT s.*,t.id turn_id,t.node_run_id,t.provider_turn_id,t.sequence turn_sequence,t.status turn_status,t.failure_code turn_failure_code,t.failure_message turn_failure_message,t.started_at turn_started_at,t.finished_at turn_finished_at,t.created_at turn_created_at,t.updated_at turn_updated_at FROM agent_execution_turns t JOIN agent_execution_sessions s ON s.id=t.agent_session_id WHERE t.node_run_id=? FOR UPDATE OF s,t",
                (rs,row) -> new AgentExecutionAllocation(this.session(rs,row), this.turn(rs)), nodeRunId);
        if (target.isEmpty()) return Optional.empty();
        final AgentExecutionAllocation allocation = target.getFirst();
        if (allocation.turn().status() != AgentExecutionTurnStatus.QUEUED || allocation.session().leaseOwnerId() != null) return Optional.empty();
        final Integer earlier = this.jdbc.queryForObject("SELECT count(*) FROM agent_execution_turns WHERE agent_session_id=? AND status='QUEUED' AND sequence<?",
                Integer.class, allocation.session().id(), allocation.turn().sequence());
        if (earlier != null && earlier > 0) return Optional.empty();
        final String nextStatus = allocation.session().providerConversationId() == null ? "CREATING" : "RESUMING";
        final List<Long> tokens = this.jdbc.query(
                "UPDATE agent_execution_sessions SET lease_owner_id=?,lease_token=lease_token+1,lease_expires_at=CURRENT_TIMESTAMP + INTERVAL '30 seconds',active_node_run_id=?,status=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_owner_id IS NULL AND active_node_run_id IS NULL AND status IN ('WAITING','IDLE') RETURNING lease_token",
                (rs, row) -> rs.getLong(1), ownerId, nodeRunId, nextStatus, allocation.session().id());
        if (tokens.isEmpty()) return Optional.empty();
        final long token = tokens.getFirst();
        this.jdbc.update("UPDATE agent_execution_turns SET status='STARTING',started_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='QUEUED'", allocation.turn().id());
        this.jdbc.update("UPDATE node_runs SET status='RUNNING',started_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'", nodeRunId);
        final Instant expiry = this.jdbc.queryForObject("SELECT lease_expires_at FROM agent_execution_sessions WHERE id=?", (rs,row)->rs.getTimestamp(1).toInstant(), allocation.session().id());
        return Optional.of(new AgentSessionExecutionClaim(allocation.session().id(), allocation.turn().id(), nodeRunId, ownerId, token, expiry,
                allocation.session().providerConversationId(), allocation.session().providerId(), allocation.session().contextMode(),
                allocation.session().providerVersion()));
    }

    @Override
    @Transactional
    public boolean renew(final UUID sessionId, final String ownerId, final long token) {
        return this.jdbc.update("UPDATE agent_execution_sessions SET lease_expires_at=CURRENT_TIMESTAMP + INTERVAL '30 seconds',updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_owner_id=? AND lease_token=? AND lease_expires_at>CURRENT_TIMESTAMP",
                sessionId, ownerId, token) == 1;
    }

    @Override
    @Transactional
    public boolean persistProviderConversation(final UUID sessionId, final String ownerId, final long token, final String conversationId, final String providerVersion) {
        return this.jdbc.update("UPDATE agent_execution_sessions SET provider_conversation_id=?,provider_version=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_owner_id=? AND lease_token=? AND lease_expires_at>CURRENT_TIMESTAMP",
                conversationId, providerVersion, sessionId, ownerId, token) == 1;
    }

    @Override
    @Transactional
    public boolean persistProviderTurn(final UUID sessionId, final UUID turnId, final String ownerId, final long token, final String providerTurnId) {
        final List<UUID> guarded = this.jdbc.query("SELECT id FROM agent_execution_sessions WHERE id=? AND lease_owner_id=? AND lease_token=? AND lease_expires_at>CURRENT_TIMESTAMP FOR UPDATE",
                (rs,row) -> rs.getObject(1, UUID.class), sessionId, ownerId, token);
        if (guarded.size() != 1) return false;
        final int changed = this.jdbc.update("UPDATE agent_execution_turns SET provider_turn_id=?,status='ACTIVE',updated_at=CURRENT_TIMESTAMP WHERE id=? AND agent_session_id=? AND status='STARTING'",
                providerTurnId, turnId, sessionId);
        if (changed != 1) return false;
        return this.jdbc.update("UPDATE agent_execution_sessions SET status='ACTIVE',updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_owner_id=? AND lease_token=?", sessionId, ownerId, token) == 1;
    }

    @Override
    @Transactional
    public boolean lockCurrentLease(final UUID sessionId, final String ownerId, final long token) {
        return this.jdbc.query("SELECT id FROM agent_execution_sessions WHERE id=? AND lease_owner_id=? AND lease_token=? AND lease_expires_at>CURRENT_TIMESTAMP FOR UPDATE",
                (rs,row) -> rs.getObject(1, UUID.class), sessionId, ownerId, token).size() == 1;
    }

    @Override
    @Transactional
    public boolean finish(final UUID sessionId, final UUID turnId, final String ownerId, final long token,
                          final AgentExecutionTurnStatus turnStatus, final String failureCode,
                          final String failureMessage, final boolean sessionCorrupting) {
        if (!this.lockCurrentLease(sessionId, ownerId, token)) return false;
        final AgentExecutionSession session = this.jdbc.queryForObject("SELECT * FROM agent_execution_sessions WHERE id=?", this::session, sessionId);
        final int turnChanged = this.jdbc.update("UPDATE agent_execution_turns SET status=?,failure_code=?,failure_message=?,finished_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND agent_session_id=? AND status IN ('STARTING','ACTIVE')",
                turnStatus.name(), failureCode, failureMessage, turnId, sessionId);
        if (turnChanged != 1) return false;
        final boolean fresh = session.contextMode() == NodeContextMode.FRESH_EACH_NODE_RUN;
        final String status = fresh ? "CLOSED" : sessionCorrupting ? "FAILED" : "IDLE";
        final String outcome = fresh ? switch (turnStatus) {
            case SUCCEEDED -> "SUCCEEDED"; case CANCELLED -> "CANCELLED"; default -> "FAILED";
        } : null;
        return this.jdbc.update("UPDATE agent_execution_sessions SET status=?,terminal_outcome=?,active_node_run_id=NULL,lease_owner_id=NULL,lease_expires_at=NULL,failure_code=?,failure_message=?,closed_at=CASE WHEN ?='CLOSED' THEN CURRENT_TIMESTAMP ELSE NULL END,updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_owner_id=? AND lease_token=? AND lease_expires_at>CURRENT_TIMESTAMP",
                status, outcome, sessionCorrupting ? failureCode : null, sessionCorrupting ? failureMessage : null,
                status, sessionId, ownerId, token) == 1;
    }

    @Override
    @Transactional
    public int recoverExpired(final String ownerId) {
        final List<RecoveryCandidate> expired = this.jdbc.query(
                "SELECT id,workflow_run_id,active_node_run_id FROM agent_execution_sessions WHERE lease_owner_id IS NOT NULL AND lease_expires_at<=CURRENT_TIMESTAMP AND status IN ('CREATING','RESUMING','ACTIVE')",
                (rs, row) -> new RecoveryCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getObject("workflow_run_id", UUID.class),
                        rs.getObject("active_node_run_id", UUID.class)
                ));
        int recovered = 0;
        for (RecoveryCandidate candidate : expired) {
            final UUID sessionId = candidate.sessionId();
            this.jdbc.query("SELECT id FROM workflow_runs WHERE id=? FOR UPDATE",
                    (rs, row) -> rs.getObject(1, UUID.class), candidate.workflowRunId());
            this.jdbc.query("SELECT id FROM node_runs WHERE id=? FOR UPDATE",
                    (rs, row) -> rs.getObject(1, UUID.class), candidate.nodeRunId());
            final List<Long> tokens = this.jdbc.query(
                    "UPDATE agent_execution_sessions SET lease_owner_id=?,lease_token=lease_token+1,lease_expires_at=CURRENT_TIMESTAMP + INTERVAL '30 seconds',updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_expires_at<=CURRENT_TIMESTAMP RETURNING lease_token",
                    (rs, row) -> rs.getLong(1), ownerId, sessionId);
            if (tokens.isEmpty()) continue;
            final long token = tokens.getFirst();
            final AgentExecutionSession session = this.jdbc.queryForObject("SELECT * FROM agent_execution_sessions WHERE id=?", this::session, sessionId);
            final List<UUID> turns = this.jdbc.query("SELECT id FROM agent_execution_turns WHERE agent_session_id=? AND node_run_id=? AND status IN ('STARTING','ACTIVE') FOR UPDATE",
                    (rs,row) -> rs.getObject(1, UUID.class), sessionId, session.activeNodeRunId());
            if (turns.size() != 1) continue;
            final RecoveryNode node = this.jdbc.queryForObject(
                    "SELECT status,failure_code,failure_message FROM node_runs WHERE id=? FOR UPDATE",
                    (rs, row) -> new RecoveryNode(rs.getString("status"), rs.getString("failure_code"), rs.getString("failure_message")),
                    session.activeNodeRunId()
            );
            if (node != null && node.terminal()) {
                this.reconcileRecoveredTerminal(session, turns.getFirst(), ownerId, token, node);
                recovered++;
                continue;
            }
            final String code="AGENT_CONTEXT_PERSISTENCE_FAILED";
            final String message="Agent execution ownership expired after a worker stopped. The uncertain provider operation was not resumed.";
            this.jdbc.update("UPDATE node_runs SET status='FAILED',failure_code=?,failure_message=?,finished_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING'",
                    code,message,session.activeNodeRunId());
            this.jdbc.update("UPDATE agent_execution_turns SET status='FAILED',failure_code=?,failure_message=?,finished_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    code,message,turns.getFirst());
            final boolean fresh=session.contextMode()==NodeContextMode.FRESH_EACH_NODE_RUN;
            this.jdbc.update("UPDATE agent_execution_sessions SET status=?,terminal_outcome=?,active_node_run_id=NULL,lease_owner_id=NULL,lease_expires_at=NULL,failure_code=?,failure_message=?,closed_at=CASE WHEN ?='CLOSED' THEN CURRENT_TIMESTAMP ELSE NULL END,updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_owner_id=? AND lease_token=?",
                    fresh?"CLOSED":"FAILED",fresh?"FAILED":null,code,message,fresh?"CLOSED":"FAILED",sessionId,ownerId,token);
            recovered++;
        }
        return recovered;
    }

    private void reconcileRecoveredTerminal(final AgentExecutionSession session, final UUID turnId,
                                            final String ownerId, final long token, final RecoveryNode node) {
        final String turnStatus = switch (node.status()) {
            case "SUCCEEDED" -> "SUCCEEDED";
            case "CANCELLED" -> "CANCELLED";
            default -> "FAILED";
        };
        this.jdbc.update(
                "UPDATE agent_execution_turns SET status=?,failure_code=?,failure_message=?,finished_at=COALESCE(finished_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE id=?",
                turnStatus, node.failureCode(), node.failureMessage(), turnId
        );
        final boolean fresh = session.contextMode() == NodeContextMode.FRESH_EACH_NODE_RUN;
        final boolean cancelled = "CANCELLED".equals(turnStatus);
        final boolean corrupting = this.sessionCorrupting(node.failureCode());
        final String sessionStatus = fresh || cancelled ? "CLOSED" : corrupting ? "FAILED" : "IDLE";
        final String outcome = "CLOSED".equals(sessionStatus) ? turnStatus : null;
        this.jdbc.update(
                "UPDATE agent_execution_sessions SET status=?,terminal_outcome=?,active_node_run_id=NULL,lease_owner_id=NULL,lease_expires_at=NULL,failure_code=?,failure_message=?,closed_at=CASE WHEN ?='CLOSED' THEN COALESCE(closed_at,CURRENT_TIMESTAMP) ELSE NULL END,updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_owner_id=? AND lease_token=?",
                sessionStatus, outcome, corrupting ? node.failureCode() : null,
                corrupting ? node.failureMessage() : null, sessionStatus, session.id(), ownerId, token
        );
    }

    private boolean sessionCorrupting(final String code) {
        return "AGENT_CONTEXT_START_FAILED".equals(code)
                || "AGENT_CONTEXT_RESUME_FAILED".equals(code)
                || "AGENT_CONTEXT_IDENTITY_MISMATCH".equals(code)
                || "AGENT_CONTEXT_PERSISTENCE_FAILED".equals(code);
    }

    @Override
    @Transactional
    public boolean cancel(final UUID nodeRunId) {
        this.jdbc.query("SELECT id FROM node_runs WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), nodeRunId);
        final List<AgentExecutionAllocation> target = this.jdbc.query(
                "SELECT s.*,t.id turn_id,t.node_run_id,t.provider_turn_id,t.sequence turn_sequence,t.status turn_status,t.failure_code turn_failure_code,t.failure_message turn_failure_message,t.started_at turn_started_at,t.finished_at turn_finished_at,t.created_at turn_created_at,t.updated_at turn_updated_at FROM agent_execution_turns t JOIN agent_execution_sessions s ON s.id=t.agent_session_id WHERE t.node_run_id=? FOR UPDATE OF s,t",
                (rs, row) -> new AgentExecutionAllocation(this.session(rs, row), this.turn(rs)),
                nodeRunId
        );
        if (target.isEmpty() || target.getFirst().turn().status() == AgentExecutionTurnStatus.CANCELLED) {
            return false;
        }
        final AgentExecutionAllocation allocation = target.getFirst();
        if (allocation.turn().status() == AgentExecutionTurnStatus.SUCCEEDED
                || allocation.turn().status() == AgentExecutionTurnStatus.FAILED) {
            return false;
        }
        this.jdbc.update(
                "UPDATE agent_execution_turns SET status='CANCELLED',finished_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('QUEUED','STARTING','ACTIVE')",
                allocation.turn().id()
        );
        this.jdbc.update(
                "UPDATE node_runs SET status='CANCELLED',finished_at=COALESCE(finished_at,CURRENT_TIMESTAMP) WHERE id=? AND status IN ('PENDING','RUNNING')",
                nodeRunId
        );
        this.jdbc.update(
                "UPDATE agent_execution_sessions SET status='CLOSED',terminal_outcome='CANCELLED',active_node_run_id=NULL,lease_owner_id=NULL,lease_token=lease_token+CASE WHEN lease_owner_id IS NULL THEN 0 ELSE 1 END,lease_expires_at=NULL,closed_at=COALESCE(closed_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE id=? AND status NOT IN ('FAILED','CLOSED')",
                allocation.session().id()
        );
        return true;
    }

    private AgentExecutionSession session(final ResultSet rs, final int row) throws SQLException {
        return new AgentExecutionSession(rs.getObject("id", UUID.class), rs.getObject("workflow_run_id", UUID.class),
                rs.getObject("source_node_id", UUID.class), rs.getObject("source_agent_id", UUID.class), rs.getObject("repository_id", UUID.class),
                rs.getString("provider_id"), rs.getString("provider_conversation_id"), rs.getString("provider_version"), NodeContextMode.valueOf(rs.getString("context_mode")),
                AgentExecutionSessionStatus.valueOf(rs.getString("status")), enumValue(AgentExecutionTerminalOutcome.class, rs.getString("terminal_outcome")),
                rs.getObject("active_node_run_id", UUID.class), rs.getString("lease_owner_id"), rs.getLong("lease_token"), instant(rs, "lease_expires_at"),
                rs.getString("failure_code"), rs.getString("failure_message"), instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "closed_at"));
    }

    private AgentExecutionTurn turn(final ResultSet rs) throws SQLException {
        return new AgentExecutionTurn(rs.getObject("turn_id", UUID.class), rs.getObject("id", UUID.class), rs.getObject("node_run_id", UUID.class),
                rs.getString("provider_turn_id"), rs.getInt("turn_sequence"), AgentExecutionTurnStatus.valueOf(rs.getString("turn_status")),
                rs.getString("turn_failure_code"), rs.getString("turn_failure_message"), instant(rs,"turn_started_at"), instant(rs,"turn_finished_at"), instant(rs,"turn_created_at"), instant(rs,"turn_updated_at"));
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException { var value=rs.getTimestamp(name); return value == null ? null : value.toInstant(); }
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) { return value == null ? null : Enum.valueOf(type, value); }

    private record RecoveryNode(String status, String failureCode, String failureMessage) {
        boolean terminal() {
            return "SUCCEEDED".equals(this.status) || "FAILED".equals(this.status)
                    || "BLOCKED".equals(this.status) || "CANCELLED".equals(this.status);
        }
    }

    private record RecoveryCandidate(UUID sessionId, UUID workflowRunId, UUID nodeRunId) {
    }
}
