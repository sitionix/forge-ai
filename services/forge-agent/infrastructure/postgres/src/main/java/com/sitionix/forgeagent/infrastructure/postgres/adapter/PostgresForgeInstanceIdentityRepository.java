package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.port.ForgeInstanceIdentityRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresForgeInstanceIdentityRepository implements ForgeInstanceIdentityRepository {
    private final JdbcTemplate jdbc;

    public UUID getOrCreate() {
        jdbc.update("INSERT INTO forge_instance_identity(singleton,instance_id) VALUES(TRUE,?) ON CONFLICT(singleton) DO NOTHING", UUID.randomUUID());
        return jdbc.queryForObject("SELECT instance_id FROM forge_instance_identity WHERE singleton=TRUE", UUID.class);
    }
}
