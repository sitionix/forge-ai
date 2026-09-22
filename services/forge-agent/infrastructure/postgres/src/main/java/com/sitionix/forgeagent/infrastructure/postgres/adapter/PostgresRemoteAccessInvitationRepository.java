package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.RemoteAccessEndpoint;
import com.sitionix.forgeagent.domain.model.RemoteAccessInvitation;
import com.sitionix.forgeagent.domain.port.RemoteAccessInvitationRepository;
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
public class PostgresRemoteAccessInvitationRepository implements RemoteAccessInvitationRepository {
    private final JdbcTemplate jdbc;

    public void insert(RemoteAccessInvitation invitation) {
        if (invitation.consumedAt() != null || invitation.cancelledAt() != null) {
            throw new IllegalArgumentException("New invitation must be unused");
        }
        jdbc.update("""
                INSERT INTO remote_access_invitations
                (id,grantor_instance_id,ssh_host,ssh_port,ssh_username,pairing_public_key,pairing_fingerprint,created_at,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, invitation.id(), invitation.grantorInstanceId(), invitation.endpoint().host(),
                invitation.endpoint().port(), invitation.endpoint().username(), invitation.pairingPublicKey(),
                invitation.pairingFingerprint(), Timestamp.from(invitation.createdAt()), Timestamp.from(invitation.expiresAt()));
    }

    public Optional<RemoteAccessInvitation> findById(UUID id) {
        return jdbc.query("SELECT * FROM remote_access_invitations WHERE id=?", (row, number) -> map(row), id)
                .stream().findFirst();
    }

    public boolean reserve(UUID invitationId, UUID sessionId, Instant now) {
        return jdbc.update("""
                UPDATE remote_access_invitations SET consumed_at=?,redeemed_session_id=?
                WHERE id=? AND consumed_at IS NULL AND cancelled_at IS NULL AND created_at<=? AND expires_at>?
                """, Timestamp.from(now), sessionId, invitationId, Timestamp.from(now), Timestamp.from(now)) == 1;
    }

    public boolean cancel(UUID invitationId, Instant now) {
        return jdbc.update("""
                UPDATE remote_access_invitations SET cancelled_at=?
                WHERE id=? AND consumed_at IS NULL AND cancelled_at IS NULL AND created_at<=?
                """, Timestamp.from(now), invitationId, Timestamp.from(now)) == 1;
    }

    private static RemoteAccessInvitation map(ResultSet row) throws SQLException {
        return new RemoteAccessInvitation(row.getObject("id", UUID.class), row.getObject("grantor_instance_id", UUID.class),
                new RemoteAccessEndpoint(row.getString("ssh_host"), row.getInt("ssh_port"), row.getString("ssh_username")),
                row.getString("pairing_public_key"), row.getString("pairing_fingerprint"),
                instant(row, "created_at"), instant(row, "expires_at"), instant(row, "consumed_at"),
                instant(row, "cancelled_at"), row.getObject("redeemed_session_id", UUID.class));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
