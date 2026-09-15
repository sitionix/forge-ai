package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.AgentExecutionSessionRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
    public Optional<AgentExecutionSession> findSession(final UUID sessionId) {
        return this.jdbc.query("SELECT * FROM agent_execution_sessions WHERE id=?", this::session, sessionId).stream().findFirst();
    }

    @Override
    public void lockReusableScope(final UUID workflowRunId, final UUID sourceNodeId, final UUID repositoryId) {
        this.jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                workflowRunId + ":" + sourceNodeId + ":" + repositoryId);
    }

    @Override
    public Optional<AgentExecutionSession> lockSession(final UUID sessionId) {
        return this.jdbc.query("SELECT * FROM agent_execution_sessions WHERE id=? FOR UPDATE", this::session, sessionId).stream().findFirst();
    }

    @Override
    public boolean hasPendingTurns(final UUID sessionId) {
        return Boolean.TRUE.equals(this.jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM agent_execution_turns WHERE agent_session_id=? AND status IN ('QUEUED','STARTING','ACTIVE'))", Boolean.class, sessionId));
    }

    @Override
    public void markContextReset(final UUID sessionId) {
        this.jdbc.update("UPDATE agent_execution_sessions SET context_reset_at=clock_timestamp() WHERE id=? AND context_reset_at IS NULL", sessionId);
    }

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
        this.jdbc.update("INSERT INTO agent_execution_turns(id,agent_session_id,node_run_id,sequence,status,event_capture_status,created_at,updated_at) VALUES (?,?,?,?,?,'NOT_STARTED',?,?)",
                turnId, session.id(), nodeRun.id(), sequence, AgentExecutionTurnStatus.QUEUED.name(),
                Timestamp.from(now), Timestamp.from(now));
        return this.findByNodeRunId(nodeRun.id()).orElseThrow();
    }

    private AgentExecutionSession findOrCreateReusable(final NodeRun nodeRun, final String providerId) {
        this.lockReusableScope(nodeRun.workflowRunId(), nodeRun.sourceNodeId(), nodeRun.repositoryId());
        final List<AgentExecutionSession> existing = this.findReusable(nodeRun);
        if (!existing.isEmpty()) return existing.getFirst();
        return this.createSession(nodeRun, providerId);
    }

    private List<AgentExecutionSession> findReusable(final NodeRun nodeRun) {
        return this.jdbc.query("SELECT * FROM agent_execution_sessions WHERE workflow_run_id=? AND source_node_id=? AND context_mode='REUSE_WITHIN_WORKFLOW_NODE' AND repository_id IS NOT DISTINCT FROM ? AND context_reset_at IS NULL FOR UPDATE",
                this::session, nodeRun.workflowRunId(), nodeRun.sourceNodeId(), nodeRun.repositoryId());
    }

    private AgentExecutionSession createSession(final NodeRun nodeRun, final String providerId) {
        if (providerId == null || providerId.isBlank()) throw new ConflictException("AGENT_CONTEXT_PERSISTENCE_FAILED", "Execution provider is required for an agent context.");
        final UUID id = UUID.randomUUID();
        final Instant now = Instant.now();
        this.jdbc.update("INSERT INTO agent_execution_sessions(id,workflow_run_id,source_node_id,source_agent_id,repository_id,provider_id,context_mode,status,lease_token,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,0,?,?)",
                id, nodeRun.workflowRunId(), nodeRun.sourceNodeId(), nodeRun.sourceAgentId(), nodeRun.repositoryId(), providerId,
                nodeRun.contextMode().name(), AgentExecutionSessionStatus.WAITING.name(),
                Timestamp.from(now), Timestamp.from(now));
        return this.jdbc.queryForObject("SELECT * FROM agent_execution_sessions WHERE id=?", this::session, id);
    }

    @Override
    public Optional<AgentExecutionAllocation> findByNodeRunId(final UUID nodeRunId) {
        final List<AgentExecutionAllocation> rows = this.jdbc.query("SELECT s.*,t.id turn_id,t.node_run_id,t.provider_turn_id,t.sequence turn_sequence,t.status turn_status,t.failure_code turn_failure_code,t.failure_message turn_failure_message,t.provider_recovery_state turn_provider_recovery_state,t.provider_recovery_terminal_outcome turn_provider_recovery_terminal_outcome,t.provider_recovery_checked_at turn_provider_recovery_checked_at,t.started_at turn_started_at,t.finished_at turn_finished_at,t.created_at turn_created_at,t.updated_at turn_updated_at FROM agent_execution_turns t JOIN agent_execution_sessions s ON s.id=t.agent_session_id WHERE t.node_run_id=?",
                (rs, row) -> new AgentExecutionAllocation(this.session(rs, row), this.turn(rs)), nodeRunId);
        return rows.stream().findFirst();
    }

    @Override
    public List<AgentExecutionAllocation> findByWorkflowRunId(final UUID workflowRunId) {
        return this.jdbc.query("SELECT s.*,t.id turn_id,t.node_run_id,t.provider_turn_id,t.sequence turn_sequence,t.status turn_status,t.failure_code turn_failure_code,t.failure_message turn_failure_message,t.provider_recovery_state turn_provider_recovery_state,t.provider_recovery_terminal_outcome turn_provider_recovery_terminal_outcome,t.provider_recovery_checked_at turn_provider_recovery_checked_at,t.started_at turn_started_at,t.finished_at turn_finished_at,t.created_at turn_created_at,t.updated_at turn_updated_at FROM agent_execution_turns t JOIN agent_execution_sessions s ON s.id=t.agent_session_id WHERE s.workflow_run_id=? ORDER BY s.created_at,t.sequence",
                (rs,row) -> new AgentExecutionAllocation(this.session(rs,row), this.turn(rs)), workflowRunId);
    }

    @Override
    @Transactional
    public Optional<AgentSessionExecutionClaim> acquire(final UUID nodeRunId, final String ownerId) {
        final List<AgentExecutionAllocation> target = this.jdbc.query("SELECT s.*,t.id turn_id,t.node_run_id,t.provider_turn_id,t.sequence turn_sequence,t.status turn_status,t.failure_code turn_failure_code,t.failure_message turn_failure_message,t.provider_recovery_state turn_provider_recovery_state,t.provider_recovery_terminal_outcome turn_provider_recovery_terminal_outcome,t.provider_recovery_checked_at turn_provider_recovery_checked_at,t.started_at turn_started_at,t.finished_at turn_finished_at,t.created_at turn_created_at,t.updated_at turn_updated_at FROM agent_execution_turns t JOIN agent_execution_sessions s ON s.id=t.agent_session_id WHERE t.node_run_id=? FOR UPDATE OF s,t",
                (rs,row) -> new AgentExecutionAllocation(this.session(rs,row), this.turn(rs)), nodeRunId);
        if (target.isEmpty()) return Optional.empty();
        final AgentExecutionAllocation allocation = target.getFirst();
        if (allocation.turn().status() != AgentExecutionTurnStatus.QUEUED || allocation.session().leaseOwnerId() != null) return Optional.empty();
        final Integer earlier = this.jdbc.queryForObject("SELECT count(*) FROM agent_execution_turns WHERE agent_session_id=? AND status='QUEUED' AND sequence<?",
                Integer.class, allocation.session().id(), allocation.turn().sequence());
        if (earlier != null && earlier > 0) return Optional.empty();
        final String nextStatus = allocation.session().providerConversationId() == null ? "CREATING" : "RESUMING";
        final List<Long> tokens = this.jdbc.query(
                "UPDATE agent_execution_sessions SET lease_owner_id=?,lease_token=lease_token+1,lease_expires_at=CURRENT_TIMESTAMP + INTERVAL '30 seconds',active_node_run_id=?,status=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND context_reset_at IS NULL AND lease_owner_id IS NULL AND active_node_run_id IS NULL AND status IN ('WAITING','IDLE') RETURNING lease_token",
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
        if (!this.lockCurrentLease(sessionId, ownerId, token)) return false;
        return this.jdbc.update("UPDATE agent_execution_sessions SET lease_expires_at=clock_timestamp() + INTERVAL '30 seconds',updated_at=clock_timestamp() WHERE id=?",
                sessionId) == 1;
    }

    @Override
    @Transactional
    public boolean persistProviderConversation(final UUID sessionId, final String ownerId, final long token, final String conversationId, final String providerVersion) {
        if (!this.lockCurrentLease(sessionId, ownerId, token)) return false;
        return this.jdbc.update("UPDATE agent_execution_sessions SET provider_conversation_id=?,provider_version=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                conversationId, providerVersion, sessionId) == 1;
    }

    @Override
    @Transactional
    public boolean persistProviderTurn(final UUID sessionId, final UUID turnId, final String ownerId, final long token, final String providerTurnId) {
        if (!this.lockCurrentLease(sessionId, ownerId, token)) return false;
        final int changed = this.jdbc.update("UPDATE agent_execution_turns SET provider_turn_id=?,status='ACTIVE',updated_at=CURRENT_TIMESTAMP WHERE id=? AND agent_session_id=? AND status='STARTING'",
                providerTurnId, turnId, sessionId);
        if (changed != 1) return false;
        return this.jdbc.update("UPDATE agent_execution_sessions SET status='ACTIVE',updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_owner_id=? AND lease_token=?", sessionId, ownerId, token) == 1;
    }

    @Override
    @Transactional
    public boolean lockCurrentLease(final UUID sessionId, final String ownerId, final long token) {
        return Boolean.TRUE.equals(this.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection ->
                PostgresAgentExecutionLeaseGuard.lockCurrent(connection, sessionId, ownerId, token)));
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
    public Optional<AgentExecutionRecoveryClaim> claimExpiredRecovery(final String ownerId) {
        final List<RecoveryTarget> candidates = this.jdbc.query("""
                SELECT s.id,s.workflow_run_id,s.active_node_run_id,t.id turn_id
                  FROM agent_execution_sessions s
                  JOIN agent_execution_turns t ON t.agent_session_id=s.id AND t.node_run_id=s.active_node_run_id
                 WHERE s.lease_owner_id IS NOT NULL AND s.lease_expires_at<=CURRENT_TIMESTAMP
                   AND s.status IN ('CREATING','RESUMING','ACTIVE') AND t.status IN ('STARTING','ACTIVE')
                   AND (t.recovery_lease_expires_at IS NULL OR t.recovery_lease_expires_at<=CURRENT_TIMESTAMP)
                 ORDER BY s.workflow_run_id,s.id LIMIT 1
                """, (rs, row) -> new RecoveryTarget(rs.getObject("id", UUID.class),
                rs.getObject("workflow_run_id", UUID.class), rs.getObject("active_node_run_id", UUID.class),
                rs.getObject("turn_id", UUID.class)));
        if (candidates.isEmpty()) return Optional.empty();
        final RecoveryTarget target = candidates.getFirst();
        // A busy local write must not hold up the worker poll. Retry this candidate on a later poll.
        if (!Boolean.TRUE.equals(this.jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))", Boolean.class,
                PostgresAgentExecutionDispatchGuard.lockKey(target.sessionId())))) return Optional.empty();
        final RecoveryNode node = this.lockRecoveryNode(target.workflowRunId(), target.nodeRunId());
        if (node == null) return Optional.empty();
        final Optional<AgentExecutionSession> session = this.lockExpiredRecoverySession(
                target.sessionId(), target.workflowRunId(), target.nodeRunId());
        if (session.isEmpty()) return Optional.empty();
        if (!this.lockRecoveryTurn(target.sessionId(), target.nodeRunId(), target.turnId())) return Optional.empty();
        return this.jdbc.query("""
                WITH recovery_clock AS MATERIALIZED (SELECT clock_timestamp() checked_at)
                UPDATE agent_execution_turns t
                   SET recovery_lease_owner_id=?,recovery_lease_token=recovery_lease_token+1,
                       recovery_lease_expires_at=recovery_clock.checked_at+INTERVAL '30 seconds',updated_at=recovery_clock.checked_at
                  FROM recovery_clock
                 WHERE t.id=? AND t.agent_session_id=? AND t.node_run_id=? AND t.status IN ('STARTING','ACTIVE')
                   AND (t.recovery_lease_expires_at IS NULL OR t.recovery_lease_expires_at<=recovery_clock.checked_at)
                 RETURNING t.provider_turn_id,t.recovery_lease_token,t.recovery_lease_expires_at
                """, (rs, row) -> new AgentExecutionRecoveryClaim(target.sessionId(), target.turnId(), target.nodeRunId(),
                target.workflowRunId(), session.get().repositoryId(), session.get().providerId(), session.get().providerVersion(),
                session.get().providerConversationId(), rs.getString("provider_turn_id"), session.get().contextMode(),
                NodeRunStatus.valueOf(node.status()), node.failureCode(), node.failureMessage(), ownerId,
                rs.getLong("recovery_lease_token"), instant(rs, "recovery_lease_expires_at")),
                ownerId, target.turnId(), target.sessionId(), target.nodeRunId()).stream().findFirst();
    }

    @Override
    @Transactional
    public boolean reconcileRecovery(final AgentExecutionRecoveryClaim claim,
                                     final AgentExecutionRecoveryReconciliation reconciliation) {
        final RecoveryNode node = this.lockRecoveryNode(claim.workflowRunId(), claim.nodeRunId());
        if (node == null) return false;
        final Optional<AgentExecutionSession> lockedSession = this.lockExpiredRecoverySession(
                claim.sessionId(), claim.workflowRunId(), claim.nodeRunId());
        if (lockedSession.isEmpty()) return false;
        if (!this.lockRecoveryTurn(claim.sessionId(), claim.nodeRunId(), claim.turnId())) return false;
        // Evaluate the wall clock only after every potentially blocking row lock is held.
        final List<UUID> turns = this.jdbc.query("""
                SELECT id FROM agent_execution_turns
                 WHERE id=? AND agent_session_id=? AND node_run_id=? AND status IN ('STARTING','ACTIVE')
                   AND recovery_lease_owner_id=? AND recovery_lease_token=? AND recovery_lease_expires_at>clock_timestamp()
                """, (rs, row) -> rs.getObject(1, UUID.class), claim.turnId(), claim.sessionId(), claim.nodeRunId(),
                claim.ownerId(), claim.leaseToken());
        if (turns.isEmpty()) return false;

        final boolean forgeTerminal = reconciliation.disposition() == AgentExecutionRecoveryDisposition.FORGE_TERMINAL;
        // Terminal Forge truth may have changed while provider inspection ran outside the transaction.
        if (forgeTerminal != node.terminal() || (!forgeTerminal && !"RUNNING".equals(node.status()))) return false;
        final AgentExecutionSession session = lockedSession.get();
        final boolean fresh = session.contextMode() == NodeContextMode.FRESH_EACH_NODE_RUN;
        final String turnStatus = forgeTerminal ? switch (node.status()) {
            case "SUCCEEDED" -> "SUCCEEDED";
            case "CANCELLED" -> "CANCELLED";
            default -> "FAILED";
        } : "FAILED";
        final String failureCode = forgeTerminal ? node.failureCode() : reconciliation.failureCode();
        final String failureMessage = forgeTerminal ? node.failureMessage() : reconciliation.failureMessage();
        final String providerState = switch (reconciliation.disposition()) {
            case FORGE_TERMINAL -> null;
            case PROVIDER_TERMINAL_RESULT_LOST -> "TERMINAL";
            case PROVIDER_ACTIVE_FAIL_CLOSED -> "ACTIVE";
            case PROVIDER_UNKNOWN_FAIL_CLOSED -> "UNKNOWN";
        };
        final String providerOutcome = "TERMINAL".equals(providerState) && reconciliation.providerTerminalOutcome() != null
                ? reconciliation.providerTerminalOutcome().name() : null;
        final boolean corrupting = forgeTerminal ? this.sessionCorrupting(failureCode)
                : reconciliation.disposition() != AgentExecutionRecoveryDisposition.PROVIDER_TERMINAL_RESULT_LOST;
        final String sessionStatus = fresh || "CANCELLED".equals(turnStatus) ? "CLOSED" : corrupting ? "FAILED" : "IDLE";
        if (!forgeTerminal) {
            this.jdbc.update("UPDATE node_runs SET status='FAILED',failure_code=?,failure_message=?,finished_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING'",
                    failureCode, failureMessage, claim.nodeRunId());
        }
        this.jdbc.update("""
                UPDATE agent_execution_turns
                   SET status=?,failure_code=?,failure_message=?,
                       event_capture_status=CASE WHEN event_capture_status='ACTIVE' THEN 'DEGRADED' ELSE event_capture_status END,
                       provider_recovery_state=?,provider_recovery_terminal_outcome=?,
                       provider_recovery_checked_at=CASE WHEN ? THEN NULL ELSE CURRENT_TIMESTAMP END,
                       recovery_lease_owner_id=NULL,recovery_lease_expires_at=NULL,
                       finished_at=COALESCE(finished_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP
                 WHERE id=?
                """, turnStatus, failureCode, failureMessage, providerState, providerOutcome, forgeTerminal, claim.turnId());
        this.jdbc.update("""
                UPDATE agent_execution_sessions
                   SET status=?,terminal_outcome=?,active_node_run_id=NULL,lease_owner_id=NULL,lease_expires_at=NULL,
                       lease_token=lease_token+1,failure_code=?,failure_message=?,
                       closed_at=CASE WHEN ?='CLOSED' THEN COALESCE(closed_at,CURRENT_TIMESTAMP) ELSE NULL END,
                       updated_at=CURRENT_TIMESTAMP
                 WHERE id=?
                """, sessionStatus, "CLOSED".equals(sessionStatus) ? turnStatus : null,
                corrupting ? failureCode : null, corrupting ? failureMessage : null, sessionStatus, claim.sessionId());
        return true;
    }

    private RecoveryNode lockRecoveryNode(final UUID workflowRunId, final UUID nodeRunId) {
        // Match normal completion: WorkflowRun -> NodeRun -> session -> exact turn.
        final List<UUID> workflows = this.jdbc.query("SELECT id FROM workflow_runs WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), workflowRunId);
        if (workflows.isEmpty()) return null;
        return this.jdbc.query("SELECT status,failure_code,failure_message FROM node_runs WHERE id=? AND workflow_run_id=? FOR UPDATE",
                (rs, row) -> new RecoveryNode(rs.getString("status"), rs.getString("failure_code"), rs.getString("failure_message")),
                nodeRunId, workflowRunId).stream().findFirst().orElse(null);
    }

    private Optional<AgentExecutionSession> lockExpiredRecoverySession(final UUID sessionId, final UUID workflowRunId,
                                                                      final UUID nodeRunId) {
        return this.jdbc.query("""
                SELECT * FROM agent_execution_sessions
                 WHERE id=? AND workflow_run_id=? AND active_node_run_id=?
                   AND lease_owner_id IS NOT NULL AND lease_expires_at<=CURRENT_TIMESTAMP
                   AND status IN ('CREATING','RESUMING','ACTIVE') FOR UPDATE
                """, this::session, sessionId, workflowRunId, nodeRunId).stream().findFirst();
    }

    private boolean lockRecoveryTurn(final UUID sessionId, final UUID nodeRunId, final UUID turnId) {
        return !this.jdbc.query("""
                SELECT id FROM agent_execution_turns
                 WHERE id=? AND agent_session_id=? AND node_run_id=? AND status IN ('STARTING','ACTIVE') FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), turnId, sessionId, nodeRunId).isEmpty();
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
                "SELECT s.*,t.id turn_id,t.node_run_id,t.provider_turn_id,t.sequence turn_sequence,t.status turn_status,t.failure_code turn_failure_code,t.failure_message turn_failure_message,t.provider_recovery_state turn_provider_recovery_state,t.provider_recovery_terminal_outcome turn_provider_recovery_terminal_outcome,t.provider_recovery_checked_at turn_provider_recovery_checked_at,t.started_at turn_started_at,t.finished_at turn_finished_at,t.created_at turn_created_at,t.updated_at turn_updated_at FROM agent_execution_turns t JOIN agent_execution_sessions s ON s.id=t.agent_session_id WHERE t.node_run_id=? FOR UPDATE OF s,t",
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
                "UPDATE agent_execution_turns SET status='CANCELLED',event_capture_status=CASE WHEN event_capture_status='ACTIVE' THEN 'DEGRADED' ELSE event_capture_status END,finished_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('QUEUED','STARTING','ACTIVE')",
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
                rs.getString("failure_code"), rs.getString("failure_message"), instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "closed_at"), instant(rs, "context_reset_at"));
    }

    private AgentExecutionTurn turn(final ResultSet rs) throws SQLException {
        return new AgentExecutionTurn(rs.getObject("turn_id", UUID.class), rs.getObject("id", UUID.class), rs.getObject("node_run_id", UUID.class),
                rs.getString("provider_turn_id"), rs.getInt("turn_sequence"), AgentExecutionTurnStatus.valueOf(rs.getString("turn_status")),
                rs.getString("turn_failure_code"), rs.getString("turn_failure_message"),
                enumValue(ProviderTurnRecoveryState.class, rs.getString("turn_provider_recovery_state")),
                enumValue(ProviderTurnRecoveryTerminalOutcome.class, rs.getString("turn_provider_recovery_terminal_outcome")),
                instant(rs, "turn_provider_recovery_checked_at"), instant(rs,"turn_started_at"), instant(rs,"turn_finished_at"), instant(rs,"turn_created_at"), instant(rs,"turn_updated_at"));
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException { var value=rs.getTimestamp(name); return value == null ? null : value.toInstant(); }
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) { return value == null ? null : Enum.valueOf(type, value); }

    private record RecoveryNode(String status, String failureCode, String failureMessage) {
        boolean terminal() {
            return "SUCCEEDED".equals(this.status) || "FAILED".equals(this.status)
                    || "BLOCKED".equals(this.status) || "CANCELLED".equals(this.status);
        }
    }

    private record RecoveryTarget(UUID sessionId, UUID workflowRunId, UUID nodeRunId, UUID turnId) {
    }
}
