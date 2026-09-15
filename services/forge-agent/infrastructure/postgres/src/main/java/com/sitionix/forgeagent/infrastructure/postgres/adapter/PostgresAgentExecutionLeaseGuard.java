package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Shared authoritative lease predicate; the caller owns the transaction on this connection. */
final class PostgresAgentExecutionLeaseGuard {
    private PostgresAgentExecutionLeaseGuard() { }

    static boolean lockCurrent(final Connection connection, final UUID sessionId,
                               final String ownerId, final long token) throws SQLException {
        try (var lock = connection.prepareStatement("SELECT id FROM agent_execution_sessions WHERE id=? FOR UPDATE")) {
            lock.setObject(1, sessionId);
            try (var row = lock.executeQuery()) {
                if (!row.next()) return false;
            }
        }
        // Evaluate wall time and independent recovery ownership only after the row lock is held.
        try (var check = connection.prepareStatement("""
                SELECT EXISTS(SELECT 1 FROM agent_execution_sessions s
                 WHERE s.id=? AND s.lease_owner_id=? AND s.lease_token=? AND s.lease_expires_at>clock_timestamp()
                   AND NOT EXISTS(SELECT 1 FROM agent_execution_turns t
                       WHERE t.agent_session_id=s.id AND t.node_run_id=s.active_node_run_id
                         AND t.recovery_lease_owner_id IS NOT NULL))
                """)) {
            check.setObject(1, sessionId);
            check.setString(2, ownerId);
            check.setLong(3, token);
            try (var row = check.executeQuery()) {
                return row.next() && row.getBoolean(1);
            }
        }
    }
}
