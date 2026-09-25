package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.McpToolInventoryRepository;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PostgresMcpToolInventoryRepository implements McpToolInventoryRepository {
    private final JdbcTemplate jdbc;
    private final PostgresMcpConnectionRepository connections;
    private final TransactionTemplate transactions;

    public PostgresMcpToolInventoryRepository(JdbcTemplate jdbc, PostgresMcpConnectionRepository connections,
                                              PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override public List<McpToolSummary> list(UUID installation, UUID id) {
        return jdbc.query("SELECT i.tool_name,i.description,i.schema_fingerprint FROM mcp_discovered_tools i "
                        + "JOIN mcp_connections c ON c.id=i.connection_id WHERE c.installation_id=? AND c.id=? ORDER BY i.tool_name",
                (rs, row) -> new McpToolSummary(rs.getString(1), rs.getString(2), rs.getString(3)), installation, id);
    }

    @Override public void replace(UUID installation, UUID id, URI endpoint, McpAuthType authType,
                                  McpEncryptedCredential credential, List<McpToolSummary> tools) {
        if (tools == null) throw new IllegalArgumentException("Invalid MCP inventory");
        transactions.executeWithoutResult(status -> {
            McpConnection current = lock(installation, id);
            if (!current.endpoint().equals(endpoint) || current.authType() != authType
                    || !Objects.equals(connections.credential(installation, id).orElse(null), credential))
                throw new IllegalStateException("MCP connection changed during probe");
            jdbc.update("DELETE FROM mcp_discovered_tools WHERE connection_id=?", id);
            for (McpToolSummary tool : tools)
                jdbc.update("INSERT INTO mcp_discovered_tools(connection_id,tool_name,description,schema_fingerprint) VALUES(?,?,?,?)",
                        id, tool.name(), tool.description(), tool.schemaFingerprint());
            jdbc.update("DELETE FROM mcp_allowed_tools a WHERE a.connection_id=? AND NOT EXISTS "
                    + "(SELECT 1 FROM mcp_discovered_tools i WHERE i.connection_id=a.connection_id "
                    + "AND i.tool_name=a.tool_name AND i.schema_fingerprint=a.schema_fingerprint)", id);
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("UPDATE mcp_connections SET checked_at=?,safe_diagnostic=NULL,updated_at=? WHERE installation_id=? AND id=?",
                    now, now, installation, id);
        });
    }

    @Override public McpConnection approve(UUID installation, UUID id, Set<McpAllowedTool> tools) {
        if (tools == null) throw new IllegalArgumentException("Invalid MCP tool approval");
        return transactions.execute(status -> {
            lock(installation, id);
            Set<McpAllowedTool> discovered = new HashSet<>();
            for (McpToolSummary tool : list(installation, id))
                discovered.add(new McpAllowedTool(tool.name(), tool.schemaFingerprint()));
            if (!discovered.containsAll(tools)) throw new IllegalArgumentException("Unknown or changed MCP tool");
            jdbc.update("DELETE FROM mcp_allowed_tools WHERE connection_id=?", id);
            for (McpAllowedTool tool : tools)
                jdbc.update("INSERT INTO mcp_allowed_tools(connection_id,tool_name,schema_fingerprint) VALUES(?,?,?)",
                        id, tool.name(), tool.schemaFingerprint());
            jdbc.update("UPDATE mcp_connections SET updated_at=? WHERE installation_id=? AND id=?",
                    Timestamp.from(Instant.now()), installation, id);
            return connections.findById(installation, id).orElseThrow();
        });
    }

    private McpConnection lock(UUID installation, UUID id) {
        List<UUID> rows = jdbc.queryForList("SELECT id FROM mcp_connections WHERE installation_id=? AND id=? FOR UPDATE",
                UUID.class, installation, id);
        if (rows.isEmpty()) throw new NoSuchElementException("MCP connection not found");
        return connections.findById(installation, id).orElseThrow();
    }
}
