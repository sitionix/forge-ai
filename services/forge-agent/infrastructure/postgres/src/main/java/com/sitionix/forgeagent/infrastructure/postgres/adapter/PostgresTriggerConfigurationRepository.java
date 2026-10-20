package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.TriggerConfigurationRepository;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresTriggerConfigurationRepository implements TriggerConfigurationRepository {
    private final JdbcTemplate jdbc;
    public PostgresTriggerConfigurationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public boolean lockConnection(UUID installationId, UUID connectionId) {
        return !jdbc.queryForList("SELECT id FROM mcp_connections WHERE installation_id=? AND id=? FOR UPDATE",
                UUID.class,installationId,connectionId).isEmpty();
    }
    public List<TriggerConfiguration> findSummaries(UUID connectionId) {
        return jdbc.query("SELECT id,connection_id,name FROM mcp_trigger_configurations WHERE connection_id=? ORDER BY lower(name),id",
                (r,n)->new TriggerConfiguration(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3)),connectionId);
    }
    public Optional<TriggerConfigurationDocument> findById(UUID connectionId, UUID id) {
        return jdbc.query("SELECT id,connection_id,name,definition FROM mcp_trigger_configurations WHERE connection_id=? AND id=?",
                (r,n)->new TriggerConfigurationDocument(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4)),connectionId,id)
                .stream().findFirst();
    }
    public void save(TriggerConfigurationDocument document) {
        int written=jdbc.update("""
                INSERT INTO mcp_trigger_configurations(id,connection_id,name,definition) VALUES(?,?,?,?::jsonb)
                ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,definition=EXCLUDED.definition
                WHERE mcp_trigger_configurations.connection_id=EXCLUDED.connection_id
                """,document.id(),document.connectionId(),document.name(),document.definitionJson());
        if(written!=1) throw new IllegalStateException("Configuration ownership changed.");
    }
}
