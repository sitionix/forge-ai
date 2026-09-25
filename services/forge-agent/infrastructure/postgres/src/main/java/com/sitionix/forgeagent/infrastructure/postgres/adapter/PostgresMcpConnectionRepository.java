package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.McpConnectionRepository;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.UnaryOperator;

@Repository
public class PostgresMcpConnectionRepository implements McpConnectionRepository {
    private static final String SELECT = "SELECT m.*,p.project_ids,t.tool_names,t.schema_fingerprints FROM mcp_connections m "
            + "LEFT JOIN LATERAL (SELECT array_agg(project_id) project_ids FROM mcp_connection_projects WHERE connection_id=m.id) p ON TRUE "
            + "LEFT JOIN LATERAL (SELECT array_agg(tool_name ORDER BY tool_name,schema_fingerprint) tool_names,"
            + "array_agg(schema_fingerprint ORDER BY tool_name,schema_fingerprint) schema_fingerprints "
            + "FROM mcp_allowed_tools WHERE connection_id=m.id) t ON TRUE ";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public PostgresMcpConnectionRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override public boolean hasRetainedCredentials() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM mcp_connection_credentials) OR EXISTS "
                        + "(SELECT 1 FROM mcp_connections WHERE credential_configured=TRUE)", Boolean.class));
    }

    public Optional<McpConnection> findById(UUID installationId, UUID id) {
        return jdbc.query(SELECT + "WHERE m.installation_id=? AND m.id=?", mapper(),installationId,id)
                .stream().findFirst();
    }
    public List<McpConnection> findAll(UUID installationId) {
        return jdbc.query(SELECT + "WHERE m.installation_id=? ORDER BY m.display_name,m.id", mapper(),installationId);
    }
    public Optional<McpEncryptedCredential> credential(UUID installationId, UUID id) {
        return jdbc.query("SELECT c.key_id,c.ciphertext FROM mcp_connection_credentials c JOIN mcp_connections m ON m.id=c.connection_id WHERE m.installation_id=? AND m.id=?",
                (rs,row) -> new McpEncryptedCredential(rs.getString(1),rs.getBytes(2)),installationId,id).stream().findFirst();
    }
    public void insert(McpConnectionState state) {
        McpConnection c = state.connection();
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO mcp_connections(id,installation_id,display_name,endpoint,auth_type,enabled,project_scope,credential_configured,created_at,updated_at,checked_at,safe_diagnostic) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    c.id(),c.installationId(),c.displayName(),c.endpoint().toString(),c.authType().name(),c.enabled(),c.projectAccess().scope().name(),c.credentialConfigured(),
                    Timestamp.from(c.createdAt()),Timestamp.from(c.updatedAt()),timestamp(c.checkedAt()),c.safeDiagnostic());
            writeChildren(state);
        });
    }
    public Optional<McpConnectionState> change(UUID installationId, UUID id, UnaryOperator<McpConnectionState> mutation) {
        return transactions.execute(status -> {
            var locked = jdbc.queryForList("SELECT id FROM mcp_connections WHERE installation_id=? AND id=? FOR UPDATE",UUID.class,installationId,id);
            if (locked.isEmpty()) return Optional.empty();
            var current = new McpConnectionState(findById(installationId,id).orElseThrow(),credential(installationId,id).orElse(null));
            var next = mutation.apply(current);
            if (next == null) {
                jdbc.update("DELETE FROM mcp_connections WHERE installation_id=? AND id=?",installationId,id);
                return Optional.empty();
            }
            McpConnection c = next.connection();
            if (!c.id().equals(id) || !c.installationId().equals(installationId)) throw new IllegalArgumentException("MCP connection owner mismatch");
            if (!current.connection().endpoint().equals(c.endpoint()) || current.connection().authType() != c.authType()
                    || (current.connection().checkedAt() != null && c.checkedAt() == null))
                jdbc.update("DELETE FROM mcp_discovered_tools WHERE connection_id=?", id);
            int affected = jdbc.update("UPDATE mcp_connections SET display_name=?,endpoint=?,auth_type=?,enabled=?,project_scope=?,credential_configured=?,updated_at=?,checked_at=?,safe_diagnostic=? WHERE installation_id=? AND id=?",
                    c.displayName(),c.endpoint().toString(),c.authType().name(),c.enabled(),c.projectAccess().scope().name(),c.credentialConfigured(),
                    Timestamp.from(c.updatedAt()),timestamp(c.checkedAt()),c.safeDiagnostic(),installationId,id);
            if (affected != 1) throw new IllegalStateException("MCP connection changed");
            writeChildren(next);
            return Optional.of(next);
        });
    }
    private void writeChildren(McpConnectionState state) {
            McpConnection c = state.connection();
            jdbc.update("DELETE FROM mcp_connection_projects WHERE connection_id=?",c.id());
            for (UUID project : c.projectAccess().projectIds())
                jdbc.update("INSERT INTO mcp_connection_projects(connection_id,project_id) VALUES(?,?)",c.id(),project);
            jdbc.update("DELETE FROM mcp_allowed_tools WHERE connection_id=?",c.id());
            for (McpAllowedTool tool : c.allowedTools())
                jdbc.update("INSERT INTO mcp_allowed_tools(connection_id,tool_name,schema_fingerprint) VALUES(?,?,?)",c.id(),tool.name(),tool.schemaFingerprint());
            if (state.credential() == null) jdbc.update("DELETE FROM mcp_connection_credentials WHERE connection_id=?",c.id());
            else jdbc.update("INSERT INTO mcp_connection_credentials(connection_id,key_id,ciphertext) VALUES(?,?,?) ON CONFLICT(connection_id) DO UPDATE SET key_id=EXCLUDED.key_id,ciphertext=EXCLUDED.ciphertext",
                    c.id(),state.credential().keyId(),state.credential().bytes());
    }
    public void delete(UUID installationId, UUID id) {
        jdbc.update("DELETE FROM mcp_connections WHERE installation_id=? AND id=?",installationId,id);
    }
    private RowMapper<McpConnection> mapper() {
        return (rs,row) -> {
            UUID id = rs.getObject("id",UUID.class);
            Set<UUID> projects = new HashSet<>();
            java.sql.Array projectIds = rs.getArray("project_ids");
            if (projectIds != null) projects.addAll(Arrays.asList((UUID[]) projectIds.getArray()));
            Set<McpAllowedTool> tools = new HashSet<>();
            java.sql.Array toolNames = rs.getArray("tool_names"), fingerprints = rs.getArray("schema_fingerprints");
            if (toolNames != null) {
                String[] names = (String[]) toolNames.getArray(), hashes = (String[]) fingerprints.getArray();
                for (int i=0;i<names.length;i++) tools.add(new McpAllowedTool(names[i],hashes[i]));
            }
            return new McpConnection(id,rs.getObject("installation_id",UUID.class),rs.getString("display_name"),URI.create(rs.getString("endpoint")),
                    McpAuthType.valueOf(rs.getString("auth_type")),rs.getBoolean("enabled"),
                    new McpProjectAccess(McpProjectAccess.Scope.valueOf(rs.getString("project_scope")),projects),tools,
                    rs.getBoolean("credential_configured"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),
                    instant(rs,"checked_at"),rs.getString("safe_diagnostic"));
        };
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs,String column) throws SQLException { Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant(); }
}
