package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.RemoteAccessSessionRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresRemoteAccessSessionRepository implements RemoteAccessSessionRepository {
    private final JdbcTemplate jdbc;

    public void insert(RemoteAccessSession session) {
        if (session.status() != RemoteAccessSessionStatus.PROVISIONING || session.version() != 0) {
            throw new IllegalArgumentException("New session must be provisioning at version zero");
        }
        jdbc.update("""
                INSERT INTO remote_access_sessions
                (id,invitation_id,local_role,grantor_instance_id,accessor_instance_id,peer_display_name,
                 ssh_host,ssh_port,ssh_username,pinned_host_public_key,session_public_key,session_fingerprint,
                 local_private_key_reference,status,created_at,provisioning_expires_at,activated_at,revoke_requested_at,
                 revoked_at,connectivity,last_seen_at,last_checked_at,failure_code,failure_message,version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, session.id(), session.invitationId(), session.localRole().name(), session.grantorInstanceId(),
                session.accessorInstanceId(), session.peerDisplayName(), session.endpoint().host(), session.endpoint().port(),
                session.endpoint().username(), session.pinnedHostPublicKey(), session.sessionPublicKey(), session.sessionFingerprint(),
                session.localPrivateKeyReference(), session.status().name(), timestamp(session.createdAt()),
                timestamp(session.provisioningExpiresAt()), timestamp(session.activatedAt()), timestamp(session.revokeRequestedAt()),
                timestamp(session.revokedAt()), session.connectivity().name(), timestamp(session.lastSeenAt()),
                timestamp(session.lastCheckedAt()), session.failureCode(), session.failureMessage(), session.version());
    }

    public Optional<RemoteAccessSession> findById(UUID id) {
        return jdbc.query("SELECT * FROM remote_access_sessions WHERE id=?", (row, number) -> map(row), id)
                .stream().findFirst();
    }

    public Optional<RemoteAccessSession> findByInvitation(UUID invitationId) {
        return jdbc.query("SELECT * FROM remote_access_sessions WHERE invitation_id=?", (row, number) -> map(row), invitationId)
                .stream().findFirst();
    }

    public java.util.List<RemoteAccessSession> findLocal(UUID instanceId) {
        return jdbc.query("""
                SELECT * FROM remote_access_sessions
                WHERE (local_role='GRANTOR' AND grantor_instance_id=?) OR (local_role='ACCESSOR' AND accessor_instance_id=?)
                ORDER BY created_at,id
                """, (row, number) -> map(row), instanceId, instanceId);
    }

    public boolean recordFailure(RemoteAccessSession before, String code, String message) {
        RemoteAccessSession after = before.withFailure(code,message);
        return jdbc.update("""
                UPDATE remote_access_sessions SET failure_code=?,failure_message=?,version=?
                WHERE id=? AND version=? AND status=?
                """,after.failureCode(),after.failureMessage(),after.version(),before.id(),before.version(),before.status().name()) == 1;
    }

    public boolean transition(RemoteAccessSession before, RemoteAccessSession after) {
        // Apply only the aggregate's allowed state change; never accept an arbitrary detached replacement.
        RemoteAccessSession expected = switch (after.status()) {
            case ACTIVE -> before.activate(after.activatedAt());
            case REVOKING -> before.requestRevoke(after.revokeRequestedAt());
            case REVOKED -> before.confirmRevoked(after.revokedAt());
            case PROVISIONING -> throw new IllegalArgumentException("Cannot transition back to provisioning");
        };
        if (!expected.equals(after) || after.version() != before.version() + 1) {
            throw new IllegalArgumentException("Session transition does not match its previous state");
        }
        return jdbc.update("""
                UPDATE remote_access_sessions SET status=?,activated_at=?,revoke_requested_at=?,revoked_at=?,
                  local_private_key_reference=?,version=?
                WHERE id=? AND version=? AND status=?
                """, after.status().name(), timestamp(after.activatedAt()), timestamp(after.revokeRequestedAt()),
                timestamp(after.revokedAt()), after.localPrivateKeyReference(), after.version(),
                before.id(), before.version(), before.status().name()) == 1;
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private static RemoteAccessSession map(ResultSet row) throws SQLException {
        return new RemoteAccessSession(row.getObject("id", UUID.class), row.getObject("invitation_id", UUID.class),
                RemoteAccessRole.valueOf(row.getString("local_role")), row.getObject("grantor_instance_id", UUID.class),
                row.getObject("accessor_instance_id", UUID.class), row.getString("peer_display_name"),
                new RemoteAccessEndpoint(row.getString("ssh_host"), row.getInt("ssh_port"), row.getString("ssh_username")),
                row.getString("pinned_host_public_key"), row.getString("session_public_key"), row.getString("session_fingerprint"),
                row.getObject("local_private_key_reference", UUID.class), RemoteAccessSessionStatus.valueOf(row.getString("status")),
                instant(row,"created_at"), instant(row,"provisioning_expires_at"), instant(row,"activated_at"),
                instant(row,"revoke_requested_at"), instant(row,"revoked_at"), RemoteAccessConnectivity.valueOf(row.getString("connectivity")),
                instant(row,"last_seen_at"), instant(row,"last_checked_at"), row.getString("failure_code"), row.getString("failure_message"),
                row.getLong("version"));
    }
}
