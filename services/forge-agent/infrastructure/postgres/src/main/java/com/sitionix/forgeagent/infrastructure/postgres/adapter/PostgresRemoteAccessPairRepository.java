package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.RemoteAccessPair;
import com.sitionix.forgeagent.domain.model.RemoteAccessRole;
import com.sitionix.forgeagent.domain.port.RemoteAccessPairRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresRemoteAccessPairRepository implements RemoteAccessPairRepository {
    private final JdbcTemplate jdbc;

    @Override public void insert(RemoteAccessPair pair) {
        if (pair.version()!=0 || pair.reverseSessionId()!=null || pair.localForwardRole()==RemoteAccessRole.ACCESSOR
                && pair.forwardSessionId()!=null) throw new IllegalArgumentException("New pair must be unlinked");
        jdbc.update("""
            INSERT INTO remote_access_pairs
            (id,local_forward_role,forward_session_id,reverse_session_id,reverse_invitation_id,created_at,version)
            VALUES (?,?,?,?,?,?,?)
            """,pair.id(),pair.localForwardRole().name(),pair.forwardSessionId(),pair.reverseSessionId(),
            pair.reverseInvitationId(),Timestamp.from(pair.createdAt()),pair.version());
    }

    @Override public Optional<RemoteAccessPair> findById(UUID id) {
        return jdbc.query("SELECT * FROM remote_access_pairs WHERE id=?",(row,index)->map(row),id).stream().findFirst();
    }

    @Override public Optional<RemoteAccessPair> findBySession(UUID sessionId) {
        return jdbc.query("SELECT * FROM remote_access_pairs WHERE forward_session_id=? OR reverse_session_id=?",
                (row,index)->map(row),sessionId,sessionId).stream().findFirst();
    }
    @Override public boolean isReverseInvitation(UUID invitationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM remote_access_pairs WHERE reverse_invitation_id=?)",Boolean.class,invitationId));
    }
    @Override public List<RemoteAccessPair> findConnectorPairs() {
        return jdbc.query("SELECT * FROM remote_access_pairs WHERE local_forward_role='ACCESSOR' ORDER BY created_at,id",
                (row,index)->map(row));
    }

    @Override public boolean transition(RemoteAccessPair before,RemoteAccessPair after) {
        RemoteAccessPair expected;
        if (before.forwardSessionId()==null && after.forwardSessionId()!=null)
            expected=before.withForwardSession(after.forwardSessionId());
        else if (before.reverseSessionId()==null && after.reverseSessionId()!=null)
            expected=before.withReverseSession(after.reverseSessionId());
        else throw new IllegalArgumentException("Pair transition does not add a direction");
        if (!expected.equals(after)) throw new IllegalArgumentException("Pair transition does not match previous state");
        return jdbc.update("""
            UPDATE remote_access_pairs SET forward_session_id=?,reverse_session_id=?,version=?
            WHERE id=? AND version=?
            """,after.forwardSessionId(),after.reverseSessionId(),after.version(),before.id(),before.version())==1;
    }

    private static RemoteAccessPair map(ResultSet row) throws SQLException {
        return new RemoteAccessPair(row.getObject("id",UUID.class),RemoteAccessRole.valueOf(row.getString("local_forward_role")),
            row.getObject("forward_session_id",UUID.class),row.getObject("reverse_session_id",UUID.class),
            row.getObject("reverse_invitation_id",UUID.class),row.getTimestamp("created_at").toInstant(),row.getLong("version"));
    }
}
