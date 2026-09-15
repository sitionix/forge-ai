package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.AgentSessionExecutionClaim;
import com.sitionix.forgeagent.domain.port.AgentExecutionDispatchGuard;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PostgresAgentExecutionDispatchGuard implements AgentExecutionDispatchGuard {
    private final DataSource dataSource;

    static String lockKey(final UUID sessionId) { return "agent-execution-dispatch:" + sessionId; }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void dispatch(final AgentSessionExecutionClaim claim, final Runnable writeRequest) {
        // A dedicated connection owns a session lock across a short lease transaction and the local write.
        // Recovery takes the matching transaction-level lock before any row lock. Hash collisions only serialize.
        try (Connection connection = this.dataSource.getConnection()) {
            try {
                this.advisory(connection, "pg_advisory_lock", claim.sessionId());
                if (!this.validateLease(connection, claim)) {
                    throw new ConflictException("STALE_AGENT_SESSION_LEASE", "Agent context ownership was lost.");
                }
                // The lease-check transaction has committed. Never wait for a provider response here.
                writeRequest.run();
            } finally {
                try {
                    this.advisory(connection, "pg_advisory_unlock", claim.sessionId());
                } catch (SQLException unlockFailure) {
                    // Do not return a connection with a session lock to the pool.
                    connection.abort(Runnable::run);
                    throw unlockFailure;
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not fence provider request dispatch.", exception);
        }
    }

    private boolean validateLease(final Connection connection, final AgentSessionExecutionClaim claim) throws SQLException {
        final boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            final boolean current = PostgresAgentExecutionLeaseGuard.lockCurrent(
                    connection, claim.sessionId(), claim.leaseOwnerId(), claim.leaseToken());
            connection.commit();
            return current;
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private void advisory(final Connection connection, final String function, final UUID sessionId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT " + function + "(hashtextextended(?,0))")) {
            statement.setString(1, lockKey(sessionId));
            statement.execute();
        }
    }
}
