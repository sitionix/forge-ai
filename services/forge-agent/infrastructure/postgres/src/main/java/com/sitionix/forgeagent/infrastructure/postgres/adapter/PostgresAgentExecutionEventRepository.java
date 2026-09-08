package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.AgentExecutionEvent;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventAppendResult;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventCandidate;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventCaptureStatus;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventPage;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventStatus;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventType;
import com.sitionix.forgeagent.domain.model.AgentSessionExecutionClaim;
import com.sitionix.forgeagent.domain.port.AgentExecutionEventRepository;
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
public class PostgresAgentExecutionEventRepository implements AgentExecutionEventRepository {
    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public boolean activate(final AgentSessionExecutionClaim claim) {
        return this.jdbc.update("""
                UPDATE agent_execution_turns t
                   SET event_capture_status=CASE
                         WHEN event_capture_status='DEGRADED' THEN 'DEGRADED'
                         ELSE 'ACTIVE'
                       END,
                       updated_at=CURRENT_TIMESTAMP
                  FROM agent_execution_sessions s
                 WHERE t.id=? AND t.agent_session_id=s.id AND t.node_run_id=?
                   AND t.provider_turn_id IS NOT NULL
                   AND s.id=? AND s.lease_owner_id=? AND s.lease_token=?
                   AND s.lease_expires_at>CURRENT_TIMESTAMP
                   AND t.event_capture_status IN ('NOT_STARTED','ACTIVE','DEGRADED')
                """, claim.turnId(), claim.nodeRunId(), claim.sessionId(), claim.leaseOwnerId(), claim.leaseToken()) == 1;
    }

    @Override
    @Transactional
    public AgentExecutionEventAppendResult append(final AgentSessionExecutionClaim claim,
                                                   final AgentExecutionEventCandidate candidate) {
        final List<Long> sequences = this.jdbc.query("""
                SELECT t.next_event_sequence
                  FROM agent_execution_turns t
                  JOIN agent_execution_sessions s ON s.id=t.agent_session_id
                 WHERE t.id=? AND t.agent_session_id=? AND t.node_run_id=?
                   AND t.provider_turn_id IS NOT NULL
                   AND t.event_capture_status IN ('ACTIVE','DEGRADED')
                   AND s.lease_owner_id=? AND s.lease_token=?
                   AND s.lease_expires_at>CURRENT_TIMESTAMP
                 FOR UPDATE OF t,s
                """, (rs, row) -> rs.getLong(1), claim.turnId(), claim.sessionId(), claim.nodeRunId(),
                claim.leaseOwnerId(), claim.leaseToken());
        if (sequences.size() != 1) return AgentExecutionEventAppendResult.STALE;

        if (candidate.providerEventKey() != null) {
            final Integer duplicates = this.jdbc.queryForObject("""
                    SELECT count(*) FROM agent_execution_events
                     WHERE agent_turn_id=? AND provider_event_key=?
                    """, Integer.class, claim.turnId(), candidate.providerEventKey());
            if (duplicates != null && duplicates > 0) return AgentExecutionEventAppendResult.DUPLICATE;
        }

        final long sequence = sequences.getFirst();
        this.jdbc.update("""
                INSERT INTO agent_execution_events(
                    id,agent_session_id,agent_turn_id,node_run_id,sequence,type,status,phase,
                    provider_event_key,payload,occurred_at,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), claim.sessionId(), claim.turnId(), claim.nodeRunId(), sequence,
                candidate.type().name(), enumName(candidate.status()), candidate.phase(),
                candidate.providerEventKey(), candidate.payload(), Timestamp.from(candidate.occurredAt()));
        this.jdbc.update("UPDATE agent_execution_turns SET next_event_sequence=next_event_sequence+1 WHERE id=?",
                claim.turnId());
        return AgentExecutionEventAppendResult.APPENDED;
    }

    @Override
    @Transactional
    public boolean markComplete(final AgentSessionExecutionClaim claim) {
        return this.captureStatus(claim, "COMPLETE", "ACTIVE");
    }

    @Override
    @Transactional
    public boolean markDegraded(final AgentSessionExecutionClaim claim) {
        return this.jdbc.update("""
                UPDATE agent_execution_turns t SET event_capture_status='DEGRADED',updated_at=CURRENT_TIMESTAMP
                  FROM agent_execution_sessions s
                 WHERE t.id=? AND t.agent_session_id=s.id AND t.node_run_id=?
                   AND s.id=? AND s.lease_owner_id=? AND s.lease_token=?
                   AND s.lease_expires_at>CURRENT_TIMESTAMP
                   AND t.event_capture_status IS NOT NULL
                   AND t.event_capture_status<>'COMPLETE'
                """, claim.turnId(), claim.nodeRunId(), claim.sessionId(), claim.leaseOwnerId(), claim.leaseToken()) == 1;
    }

    private boolean captureStatus(final AgentSessionExecutionClaim claim, final String target,
                                  final String expected) {
        return this.jdbc.update("""
                UPDATE agent_execution_turns t SET event_capture_status=?,updated_at=CURRENT_TIMESTAMP
                  FROM agent_execution_sessions s
                 WHERE t.id=? AND t.agent_session_id=s.id AND t.node_run_id=?
                   AND s.id=? AND s.lease_owner_id=? AND s.lease_token=?
                   AND s.lease_expires_at>CURRENT_TIMESTAMP
                   AND t.event_capture_status=?
                """, target, claim.turnId(), claim.nodeRunId(), claim.sessionId(), claim.leaseOwnerId(),
                claim.leaseToken(), expected) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentExecutionEventPage> findPage(final UUID turnId, final long afterSequence,
                                                       final int limit) {
        final List<String> statuses = this.jdbc.query(
                "SELECT event_capture_status FROM agent_execution_turns WHERE id=?",
                (rs, row) -> rs.getString(1), turnId);
        if (statuses.isEmpty()) return Optional.empty();

        final List<AgentExecutionEvent> fetched = this.jdbc.query("""
                SELECT * FROM agent_execution_events
                 WHERE agent_turn_id=? AND sequence>?
                 ORDER BY sequence ASC LIMIT ?
                """, this::event, turnId, afterSequence, limit + 1);
        final boolean hasMore = fetched.size() > limit;
        final List<AgentExecutionEvent> events = hasMore
                ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        final long lastSequence = events.isEmpty() ? afterSequence : events.getLast().sequence();
        final String storedStatus = statuses.getFirst();
        final AgentExecutionEventCaptureStatus captureStatus = storedStatus == null
                ? AgentExecutionEventCaptureStatus.UNAVAILABLE
                : AgentExecutionEventCaptureStatus.valueOf(storedStatus);
        return Optional.of(new AgentExecutionEventPage(
                turnId, captureStatus, events, lastSequence, lastSequence, hasMore));
    }

    private AgentExecutionEvent event(final ResultSet rs, final int row) throws SQLException {
        return new AgentExecutionEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("agent_session_id", UUID.class),
                rs.getObject("agent_turn_id", UUID.class),
                rs.getObject("node_run_id", UUID.class),
                rs.getLong("sequence"),
                AgentExecutionEventType.valueOf(rs.getString("type")),
                enumValue(AgentExecutionEventStatus.class, rs.getString("status")),
                rs.getString("phase"),
                rs.getString("provider_event_key"),
                rs.getString("payload"),
                instant(rs, "occurred_at"),
                instant(rs, "created_at")
        );
    }

    private static String enumName(final Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static Instant instant(final ResultSet rs, final String name) throws SQLException {
        final Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static <E extends Enum<E>> E enumValue(final Class<E> type, final String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
