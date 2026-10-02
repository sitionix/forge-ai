package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.McpOAuthTransactionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Lock order is always connection then authorization transaction. */
@Repository
public class PostgresMcpOAuthTransactionRepository implements McpOAuthTransactionRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    public PostgresMcpOAuthTransactionRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.json = json;
    }

    public void insert(McpOAuthTransaction transaction) {
        transactions.executeWithoutResult(status -> {
            if (!lockConnection(transaction.installationId(), transaction.connectionId())) throw new NoSuchElementException("MCP connection not found");
            jdbc.update("DELETE FROM mcp_oauth_transactions WHERE installation_id=? AND connection_id=?", transaction.installationId(), transaction.connectionId());
            jdbc.update("INSERT INTO mcp_oauth_transactions(id,installation_id,connection_id,state_hash,browser_hash,connection_snapshot,verifier_key_id,verifier_ciphertext,expires_at) VALUES(?,?,?,?,?,?::jsonb,?,?,?)",
                    transaction.id(), transaction.installationId(), transaction.connectionId(), transaction.stateHash(), transaction.browserHash(),
                    snapshotJson(transaction.snapshot()), transaction.verifier().keyId(), transaction.verifier().bytes(), Timestamp.from(transaction.expiresAt()));
        });
    }

    public Optional<McpOAuthTransaction> claim(UUID owner, UUID id, String stateHash, String browserHash, Instant now) {
        return transactions.execute(status -> {
            var connection = jdbc.queryForList("SELECT connection_id FROM mcp_oauth_transactions WHERE installation_id=? AND id=?", UUID.class, owner, id);
            if (connection.isEmpty() || !lockConnection(owner, connection.getFirst())) return Optional.empty();
            var rows = jdbc.query("SELECT * FROM mcp_oauth_transactions WHERE installation_id=? AND id=? FOR UPDATE", mapper(), owner, id);
            if (rows.isEmpty()) return Optional.empty();
            var transaction = rows.getFirst();
            if (!transaction.stateHash().equals(stateHash) || !transaction.browserHash().equals(browserHash)) return Optional.empty();
            if (!transaction.expiresAt().isAfter(now)) {
                jdbc.update("DELETE FROM mcp_oauth_transactions WHERE installation_id=? AND id=?", owner, id);
                return Optional.empty();
            }
            if (transaction.claimedAt() != null) return Optional.empty();
            jdbc.update("UPDATE mcp_oauth_transactions SET claimed_at=? WHERE installation_id=? AND id=?", Timestamp.from(now), owner, id);
            return Optional.of(new McpOAuthTransaction(transaction.id(), owner, transaction.connectionId(), transaction.stateHash(), transaction.browserHash(),
                    transaction.snapshot(), transaction.verifier(), transaction.expiresAt(), now));
        });
    }

    public Optional<McpOAuthTransaction> find(UUID owner, UUID connection, UUID id) {
        return jdbc.query("SELECT * FROM mcp_oauth_transactions WHERE installation_id=? AND connection_id=? AND id=?", mapper(), owner, connection, id)
                .stream().findFirst();
    }
    public void delete(UUID owner, UUID connection, UUID id) {
        transactions.executeWithoutResult(status -> {
            if (lockConnection(owner, connection)) jdbc.update("DELETE FROM mcp_oauth_transactions WHERE installation_id=? AND connection_id=? AND id=?", owner, connection, id);
        });
    }
    private boolean lockConnection(UUID owner, UUID connection) {
        return !jdbc.queryForList("SELECT id FROM mcp_connections WHERE installation_id=? AND id=? FOR UPDATE", UUID.class, owner, connection).isEmpty();
    }
    private RowMapper<McpOAuthTransaction> mapper() {
        return (rs, row) -> new McpOAuthTransaction(rs.getObject("id", UUID.class), rs.getObject("installation_id", UUID.class),
                rs.getObject("connection_id", UUID.class), rs.getString("state_hash"), rs.getString("browser_hash"), snapshot(rs.getString("connection_snapshot")),
                new McpEncryptedCredential(rs.getString("verifier_key_id"), rs.getBytes("verifier_ciphertext")), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("claimed_at") == null ? null : rs.getTimestamp("claimed_at").toInstant());
    }
    private String snapshotJson(McpConnection connection) {
        try { return json.writeValueAsString(connection); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException("OAuth transaction persistence unavailable"); }
    }
    private McpConnection snapshot(String value) {
        try { return json.readValue(value, McpConnection.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException("OAuth transaction persistence invalid"); }
    }
}
