package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.model.dialogue.*;
import com.sitionix.forgeagent.domain.port.dialogue.DialogueRepository;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class PostgresDialogueRepository implements DialogueRepository {
    private final JdbcTemplate jdbc;
    private static final String TURN_QUERY = "SELECT d.*,t.id execution_turn_id FROM dialogue_turns d LEFT JOIN agent_execution_turns t ON t.dialogue_turn_id=d.id";

    @Override
    public void create(final UUID nodeRunId) {
        this.jdbc.update("INSERT INTO dialogues(node_run_id,state) VALUES (?,'INITIALIZING') ON CONFLICT DO NOTHING", nodeRunId);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<DialogueSnapshot> find(final UUID nodeRunId) {
        return this.read(nodeRunId, false);
    }

    @Override
    public DialogueSnapshot lock(final UUID nodeRunId) {
        return this.read(nodeRunId, true).orElseThrow(() -> new NotFoundException("DIALOGUE_NOT_FOUND", "Dialogue was not found."));
    }

    private Optional<DialogueSnapshot> read(final UUID nodeRunId, final boolean lock) {
        final var headers = this.jdbc.query("SELECT * FROM dialogues WHERE node_run_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Header(DialogueState.valueOf(rs.getString("state")), rs.getLong("revision"),
                        uuid(rs,"summary_revision_id"), instant(rs,"created_at"), instant(rs,"updated_at")), nodeRunId);
        if (headers.isEmpty()) return Optional.empty();
        final Header h = headers.getFirst();
        final var latest = this.jdbc.query("SELECT * FROM dialogue_revisions WHERE node_run_id=? ORDER BY revision DESC LIMIT 1", this::revision, nodeRunId);
        final var active = this.jdbc.query(TURN_QUERY + " WHERE d.node_run_id=? AND d.status IN ('QUEUED','RUNNING')", this::turn, nodeRunId);
        final var completion = this.jdbc.query("SELECT * FROM dialogue_completions WHERE node_run_id=?", this::completion, nodeRunId);
        final Long sequence = this.jdbc.queryForObject("SELECT COALESCE(MAX(sequence),0) FROM dialogue_messages WHERE node_run_id=?", Long.class, nodeRunId);
        final Integer turns = this.jdbc.queryForObject("SELECT count(*) FROM dialogue_turns WHERE node_run_id=?", Integer.class, nodeRunId);
        return Optional.of(new DialogueSnapshot(nodeRunId, h.state(), h.revision(), h.summaryRevisionId(),
                latest.isEmpty() ? null : latest.getFirst(), active.isEmpty() ? null : active.getFirst(),
                completion.isEmpty() ? null : completion.getFirst(), sequence, turns, h.createdAt(), h.updatedAt()));
    }

    @Override
    public List<DialogueMessage> messages(final UUID nodeRunId, final long afterSequence, final int limit) {
        return this.jdbc.query("SELECT * FROM dialogue_messages WHERE node_run_id=? AND sequence>? ORDER BY sequence LIMIT ?",
                (rs,row) -> new DialogueMessage(uuid(rs,"id"), uuid(rs,"node_run_id"), rs.getLong("sequence"),
                        DialogueMessageRole.valueOf(rs.getString("role")), rs.getString("text"), uuid(rs,"turn_id"), instant(rs,"created_at")),
                nodeRunId, afterSequence, limit);
    }

    @Override
    public void appendMessage(final DialogueMessage message) {
        this.jdbc.update("INSERT INTO dialogue_messages(id,node_run_id,sequence,role,text,turn_id,created_at) VALUES (?,?,?,?,?,?,?)",
                message.id(),message.nodeRunId(),message.sequence(),message.role().name(),message.text(),message.turnId(),Timestamp.from(message.createdAt()));
    }

    @Override
    public void insertTurn(final DialogueTurn turn) {
        this.jdbc.update("INSERT INTO dialogue_turns(id,node_run_id,kind,request_id,triggering_message_id,input_revision,status,created_at) VALUES (?,?,?,?,?,?,?,?)",
                turn.id(),turn.nodeRunId(),turn.kind().name(),turn.requestId(),turn.triggeringMessageId(),turn.inputRevision(),turn.status().name(),Timestamp.from(turn.createdAt()));
    }

    @Override
    public Optional<DialogueTurn> findTurn(final UUID turnId) {
        return this.jdbc.query(TURN_QUERY + " WHERE d.id=?", this::turn, turnId).stream().findFirst();
    }

    @Override
    public List<UUID> queuedTurnIds() {
        return this.jdbc.query("SELECT id FROM dialogue_turns WHERE status='QUEUED' ORDER BY created_at", (rs,row) -> uuid(rs,"id"));
    }

    @Override
    public void startTurn(final UUID turnId) {
        this.jdbc.update("UPDATE dialogue_turns SET status='RUNNING' WHERE id=? AND status='QUEUED'", turnId);
    }

    @Override
    public void finishTurn(final UUID turnId, final DialogueTurnStatus status, final String resultJson,
                           final String failureCode, final String failureMessage) {
        this.jdbc.update("UPDATE dialogue_turns SET status=?,result=CAST(? AS jsonb),failure_code=?,failure_message=?,finished_at=clock_timestamp() WHERE id=? AND status IN ('QUEUED','RUNNING')",
                status.name(), resultJson, failureCode, failureMessage, turnId);
    }

    @Override
    public void appendRevision(final DialogueRevision revision) {
        this.jdbc.update("INSERT INTO dialogue_revisions(id,node_run_id,revision,turn_id,kind,input_revision,result,created_at) VALUES (?,?,?,?,?,?,CAST(? AS jsonb),?)",
                revision.id(),revision.nodeRunId(),revision.revision(),revision.turnId(),revision.kind().name(),revision.inputRevision(),revision.resultJson(),Timestamp.from(revision.createdAt()));
    }

    @Override
    public void updateState(final UUID nodeRunId, final DialogueState state, final long revision, final UUID summaryRevisionId) {
        this.jdbc.update("UPDATE dialogues SET state=?,revision=?,summary_revision_id=?,updated_at=clock_timestamp() WHERE node_run_id=?",
                state.name(), revision, summaryRevisionId, nodeRunId);
    }

    @Override
    public Optional<DialogueCommand> findCommand(final UUID nodeRunId, final UUID requestId) {
        return this.jdbc.query("SELECT * FROM dialogue_commands WHERE node_run_id=? AND request_id=?",
                (rs,row) -> new DialogueCommand(uuid(rs,"node_run_id"),uuid(rs,"request_id"),rs.getString("kind"),
                        rs.getString("fingerprint"),instant(rs,"created_at")), nodeRunId, requestId).stream().findFirst();
    }

    @Override
    public void recordCommand(final DialogueCommand command) {
        this.jdbc.update("INSERT INTO dialogue_commands(node_run_id,request_id,kind,fingerprint,created_at) VALUES (?,?,?,?,?)",
                command.nodeRunId(),command.requestId(),command.kind(),command.fingerprint(),Timestamp.from(command.createdAt()));
    }

    @Override
    public void complete(final DialogueCompletion completion) {
        this.jdbc.update("INSERT INTO dialogue_completions(node_run_id,request_id,summary_revision_id,output_port_id,disposition,revision,completed_at) VALUES (?,?,?,?,?,?,?)",
                completion.nodeRunId(),completion.requestId(),completion.summaryRevisionId(),completion.outputPortId(),
                completion.disposition().name(),completion.revision(),Timestamp.from(completion.completedAt()));
    }

    private DialogueRevision revision(final ResultSet rs, final int row) throws SQLException {
        return new DialogueRevision(uuid(rs,"id"),uuid(rs,"node_run_id"),rs.getLong("revision"),uuid(rs,"turn_id"),
                DialogueTurnKind.valueOf(rs.getString("kind")),rs.getLong("input_revision"),rs.getString("result"),instant(rs,"created_at"));
    }

    private DialogueTurn turn(final ResultSet rs, final int row) throws SQLException {
        return new DialogueTurn(uuid(rs,"id"),uuid(rs,"node_run_id"),DialogueTurnKind.valueOf(rs.getString("kind")),
                uuid(rs,"request_id"),uuid(rs,"triggering_message_id"),rs.getLong("input_revision"),
                DialogueTurnStatus.valueOf(rs.getString("status")),uuid(rs,"execution_turn_id"),rs.getString("result"),
                rs.getString("failure_code"),rs.getString("failure_message"),instant(rs,"created_at"),instant(rs,"finished_at"));
    }

    private DialogueCompletion completion(final ResultSet rs, final int row) throws SQLException {
        return new DialogueCompletion(uuid(rs,"node_run_id"),uuid(rs,"request_id"),uuid(rs,"summary_revision_id"),
                uuid(rs,"output_port_id"),DialogueOutputDisposition.valueOf(rs.getString("disposition")),rs.getLong("revision"),instant(rs,"completed_at"));
    }

    private static UUID uuid(final ResultSet rs, final String column) throws SQLException { return rs.getObject(column,UUID.class); }
    private static Instant instant(final ResultSet rs, final String column) throws SQLException {
        final Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record Header(DialogueState state, long revision, UUID summaryRevisionId, Instant createdAt, Instant updatedAt) { }
}
