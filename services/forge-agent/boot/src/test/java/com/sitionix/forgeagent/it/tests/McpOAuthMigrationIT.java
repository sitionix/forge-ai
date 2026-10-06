package com.sitionix.forgeagent.it.tests;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.infrastructure.local.mcp.*;
import com.sitionix.forgeagent.infrastructure.postgres.adapter.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;

/** Actual 42 -> 43 upgrade on disposable PostgreSQL; no production provisioning. */
class McpOAuthMigrationIT {
    @Test void existingCredentialsSurviveOAuthMigration() {
        try (var database = new PostgreSQLContainer<>("postgres:16-alpine")) {
            database.start();
            Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()).target("42").load().migrate();
            var datasource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
            var jdbc = new JdbcTemplate(datasource);
            var owner = new PostgresForgeInstanceIdentityRepository(jdbc).getOrCreate(); var id = UUID.randomUUID();
            var cipher = new AesGcmMcpCredentialCipher(() -> new McpLocalKeys("fixture", Map.of("fixture", new byte[32])));
            var encrypted = cipher.encrypt(owner, id, "credential", "bearer-canary".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jdbc.update("INSERT INTO mcp_connections(id,installation_id,display_name,endpoint,auth_type,enabled,project_scope,credential_configured,created_at,updated_at) VALUES(?,?,?,'https://example.org/mcp','BEARER',false,'ALL',true,now(),now())", id, owner, "Legacy bearer");
            jdbc.update("INSERT INTO mcp_connection_credentials(connection_id,key_id,ciphertext) VALUES(?,?,?)", id, encrypted.keyId(), encrypted.bytes());
            Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()).load().migrate();
            var repository = new PostgresMcpConnectionRepository(jdbc, new DataSourceTransactionManager(datasource), new ObjectMapper().findAndRegisterModules());
            var current = repository.findById(owner, id).orElseThrow();
            assertThat(current.credentialConfigured()).isTrue(); assertThat(current.oauthConfiguration()).isNull(); assertThat(current.oauthAuthorizationId()).isNull();
            assertThat(repository.credential(owner, id)).contains(encrypted);
            assertThat(new String(cipher.decrypt(owner, id, "credential", repository.credential(owner, id).orElseThrow()), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("bearer-canary");
        }
    }
}
