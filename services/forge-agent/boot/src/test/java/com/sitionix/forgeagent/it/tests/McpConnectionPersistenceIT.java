package com.sitionix.forgeagent.it.tests;

import static org.assertj.core.api.Assertions.*;
import static com.sitionix.forgeagent.it.infra.db.ForgeAgentDbContracts.PROJECT;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.application.mcp.McpConnectionService;
import com.sitionix.forgeagent.infrastructure.local.mcp.*;
import com.sitionix.forgeagent.infrastructure.postgres.adapter.PostgresMcpConnectionRepository;
import com.sitionix.forgeagent.infrastructure.postgres.adapter.PostgresMcpToolInventoryRepository;
import com.sitionix.forgeagent.infrastructure.postgres.adapter.PostgresForgeInstanceIdentityRepository;
import com.sitionix.forgeagent.AgentMcpDowngradeConfiguration;
import com.sitionix.forgeagent.RemoteAccessPairingReconciliation;
import com.sitionix.forgeagent.it.infra.ForgeAgentTestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@IntegrationTest
class McpConnectionPersistenceIT {
    @Autowired private ForgeAgentTestManager forgeIt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ProjectRepository projects;

    @Test void inventoryIsStoredAndSchemaChangeRevokesOnlyChangedApproval() {
        var connections = new PostgresMcpConnectionRepository(jdbc, transactions);
        var inventory = new PostgresMcpToolInventoryRepository(jdbc, connections, transactions);
        UUID installation = new PostgresForgeInstanceIdentityRepository(jdbc).getOrCreate();
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        URI endpoint = URI.create("https://example.org/mcp");
        var connection = new McpConnection(id, installation, "inventory", endpoint, McpAuthType.NONE,
                false, McpProjectAccess.all(), Set.of(), false, now, now, null, null);
        connections.insert(new McpConnectionState(connection, null));
        inventory.replace(installation, id, endpoint, McpAuthType.NONE, null, List.of(
                new McpToolSummary("read", "Read", "sha256:one"),
                new McpToolSummary("write", "Write", "sha256:two")));
        var approved = inventory.approve(installation, id, Set.of(
                new McpAllowedTool("read", "sha256:one"), new McpAllowedTool("write", "sha256:two")));
        assertThat(approved.allowedTools()).hasSize(2);
        assertThat(approved.checkedAt()).isNotNull();
        inventory.replace(installation, id, endpoint, McpAuthType.NONE, null, List.of(
                new McpToolSummary("read", "Read", "sha256:one"),
                new McpToolSummary("write", "Changed", "sha256:three")));
        assertThat(connections.findById(installation, id).orElseThrow().allowedTools())
                .containsExactly(new McpAllowedTool("read", "sha256:one"));
        assertThatThrownBy(() -> inventory.approve(installation, id, Set.of(new McpAllowedTool("write", "sha256:two"))))
                .isInstanceOf(IllegalArgumentException.class);
        URI changedEndpoint = URI.create("https://other.example.org/mcp");
        connections.change(installation, id, state -> {
            var c = state.connection();
            return new McpConnectionState(new McpConnection(c.id(), c.installationId(), c.displayName(),
                    changedEndpoint, c.authType(), c.enabled(), c.projectAccess(),
                    Set.of(), c.credentialConfigured(), c.createdAt(), Instant.now(), null, null), null);
        });
        assertThat(inventory.list(installation, id)).isEmpty();
        inventory.replace(installation, id, changedEndpoint, McpAuthType.NONE, null,
                List.of(new McpToolSummary("read", "Read", "sha256:one")));
        connections.change(installation, id, state -> {
            var c = state.connection();
            return new McpConnectionState(new McpConnection(c.id(), c.installationId(), c.displayName(),
                    c.endpoint(), c.authType(), c.enabled(), c.projectAccess(), Set.of(),
                    c.credentialConfigured(), c.createdAt(), Instant.now(), null, null), state.credential());
        });
        assertThat(inventory.list(installation, id)).isEmpty();
        connections.delete(installation, id);
    }

    @Test void completedProbeCannotPublishInventoryForReplacedCredential() {
        var connections = new PostgresMcpConnectionRepository(jdbc, transactions);
        var inventory = new PostgresMcpToolInventoryRepository(jdbc, connections, transactions);
        UUID installation = new PostgresForgeInstanceIdentityRepository(jdbc).getOrCreate();
        UUID id = UUID.randomUUID();
        URI endpoint = URI.create("https://example.org/mcp");
        Instant now = Instant.now();
        var oldCredential = new McpEncryptedCredential("test", new byte[]{1});
        var connection = new McpConnection(id, installation, "credential-race", endpoint, McpAuthType.BEARER,
                false, McpProjectAccess.all(), Set.of(), true, now, now, null, null);
        connections.insert(new McpConnectionState(connection, oldCredential));
        connections.change(installation, id, state ->
                new McpConnectionState(state.connection(), new McpEncryptedCredential("test", new byte[]{2})));
        assertThatThrownBy(() -> inventory.replace(installation, id, endpoint, McpAuthType.BEARER,
                oldCredential, List.of(new McpToolSummary("read", null, "sha256:one"))))
                .isInstanceOf(IllegalStateException.class).hasMessage("MCP connection changed during probe");
        assertThat(inventory.list(installation, id)).isEmpty();
        connections.delete(installation, id);
    }

    @Test void retainedCredentialProbeIgnoresConnectionEnableAndChecksBothStorageSignals() {
        var repository = new PostgresMcpConnectionRepository(jdbc,transactions);
        assertThat(repository.hasRetainedCredentials()).isFalse();
        UUID installation=new PostgresForgeInstanceIdentityRepository(jdbc).getOrCreate(), id=UUID.randomUUID();
        Instant now=Instant.now();
        var connection=new McpConnection(id,installation,"retained",URI.create("https://example.org/mcp"),
                McpAuthType.BEARER,false,McpProjectAccess.all(),Set.of(),true,now,now,null,null);
        repository.insert(new McpConnectionState(connection,new McpEncryptedCredential("k1",new byte[]{1,2,3})));
        assertThat(repository.hasRetainedCredentials()).isTrue();
        new ApplicationContextRunner()
                .withUserConfiguration(RemoteAccessPairingReconciliation.class,AgentMcpDowngradeConfiguration.class)
                .withBean(McpConnectionRepository.class,() -> repository)
                .run(context -> assertThat(context.getStartupFailure()).isNotNull()
                        .hasRootCauseMessage("MCP downgrade refused: protected material retained or unavailable"));
        assertThat(repository.credential(installation,id)).contains(new McpEncryptedCredential("k1",new byte[]{1,2,3}));
        jdbc.update("DELETE FROM mcp_connection_credentials WHERE connection_id=?",id);
        assertThat(repository.hasRetainedCredentials()).isTrue();
        jdbc.update("UPDATE mcp_connections SET credential_configured=FALSE WHERE id=?",id);
        assertThat(repository.hasRetainedCredentials()).isFalse();
        jdbc.update("INSERT INTO mcp_connection_credentials(connection_id,key_id,ciphertext) VALUES(?,?,?)",id,"k1",new byte[]{1,2,3});
        assertThat(repository.hasRetainedCredentials()).isTrue();
        repository.delete(installation,id);
        assertThat(repository.hasRetainedCredentials()).isFalse();
    }

    @Test void migrationAndRoundTripSeparateMetadataFromCiphertext() {
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public'",String.class))
                .contains("mcp_connections","mcp_connection_credentials","mcp_connection_projects","mcp_allowed_tools");
        var repository = new PostgresMcpConnectionRepository(jdbc,transactions);
        forgeIt.postgresql().create().to(PROJECT.withJson("project_alpha.json")).build();
        UUID installation = new PostgresForgeInstanceIdentityRepository(jdbc).getOrCreate(), id = UUID.randomUUID(), project = UUID.fromString("10000000-0000-4000-8000-000000000001");
        var connection = connection(id,installation,project);
        var encrypted = new McpEncryptedCredential("k1",new byte[] {1,2,3,4});
        repository.insert(new McpConnectionState(connection,encrypted));
        assertThat(repository.findById(installation,id)).contains(connection);
        assertThat(repository.findAll(installation)).contains(connection);
        assertThat(repository.findById(UUID.randomUUID(),id)).isEmpty();
        assertThat(repository.credential(installation,id)).contains(encrypted);
        assertThat(jdbc.queryForObject("SELECT key_id FROM mcp_connection_credentials WHERE connection_id=?",String.class,id)).isEqualTo("k1");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name='mcp_connections'",String.class))
                .doesNotContain("credential","ciphertext","secret");
        repository.delete(installation,id);
        assertThat(repository.findById(installation,id)).isEmpty();
        assertThat(repository.credential(installation,id)).isEmpty();
    }

    @Test void failedTransactionRollsBackMetadataAndCredentialTogether() {
        var repository = new PostgresMcpConnectionRepository(jdbc,transactions);
        forgeIt.postgresql().create().to(PROJECT.withJson("project_alpha.json")).build();
        UUID installation = new PostgresForgeInstanceIdentityRepository(jdbc).getOrCreate(), id = UUID.randomUUID();
        var connection = connection(id,installation,UUID.fromString("10000000-0000-4000-8000-000000000001"));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            repository.insert(new McpConnectionState(connection,new McpEncryptedCredential("k1",new byte[] {1,2,3})));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.findById(installation,id)).isEmpty();
        assertThat(repository.credential(installation,id)).isEmpty();
    }

    @Test void persistedAesCredentialRotatesToActiveKeyWithoutPlaintextStorage() {
        var repository = new PostgresMcpConnectionRepository(jdbc,transactions);
        var identity = new PostgresForgeInstanceIdentityRepository(jdbc);
        byte[] oldKey = new byte[32], newKey = new byte[32];
        Arrays.fill(oldKey,(byte)7); Arrays.fill(newKey,(byte)8);
        var oldCipher = new AesGcmMcpCredentialCipher(() -> new McpLocalKeys("old",Map.of("old",oldKey)));
        var service = new McpConnectionService(repository,projects,identity,oldCipher);
        var created = service.create("Bearer",URI.create("https://example.org/mcp"),McpAuthType.BEARER,
                McpProjectAccess.all(),McpCredentialSecret.bearer("synthetic-credential"));
        var before = repository.credential(created.installationId(),created.id()).orElseThrow();
        assertThat(before.keyId()).isEqualTo("old");
        assertThat(new String(before.bytes(),StandardCharsets.UTF_8)).doesNotContain("synthetic-credential");
        var activeCipher = new AesGcmMcpCredentialCipher(() -> new McpLocalKeys("new",Map.of("old",oldKey,"new",newKey)));
        new McpConnectionService(repository,projects,identity,activeCipher).reencrypt(created.id());
        var after = repository.credential(created.installationId(),created.id()).orElseThrow();
        assertThat(after.keyId()).isEqualTo("new");
        assertThat(new String(activeCipher.decrypt(created.installationId(),created.id(),"credential",after),StandardCharsets.UTF_8))
                .isEqualTo("synthetic-credential");
        assertThatThrownBy(() -> oldCipher.decrypt(created.installationId(),created.id(),"credential",after))
                .isInstanceOf(IllegalArgumentException.class);
        repository.delete(created.installationId(),created.id());
    }

    @Test void getAndListUseOneSnapshotForPolicyAndTools() {
        forgeIt.postgresql().create().to(PROJECT.withJson("project_alpha.json")).build();
        UUID project = UUID.fromString("10000000-0000-4000-8000-000000000001");
        var repository = new PostgresMcpConnectionRepository(jdbc,transactions);
        UUID installation = new PostgresForgeInstanceIdentityRepository(jdbc).getOrCreate(), id = UUID.randomUUID();
        Instant now = Instant.now();
        var all = new McpConnection(id,installation,"x",URI.create("https://example.org"),McpAuthType.NONE,true,
                McpProjectAccess.all(),Set.of(new McpAllowedTool("old","h1")),false,now,now,null,null);
        repository.insert(new McpConnectionState(all,null));
        var switched = new java.util.concurrent.atomic.AtomicBoolean();
        JdbcTemplate hooked = hookedJdbc(() -> {
            if (switched.compareAndSet(false,true)) repository.change(installation,id,state ->
                    new McpConnectionState(withPolicy(state.connection(),McpProjectAccess.selected(Set.of(project)),
                            Set.of(new McpAllowedTool("new","h2"))),null));
        });
        var before = new PostgresMcpConnectionRepository(hooked,transactions).findById(installation,id).orElseThrow();
        assertThat(before.projectAccess()).isEqualTo(McpProjectAccess.all());
        assertThat(before.allowedTools()).containsExactly(new McpAllowedTool("old","h1"));
        switched.set(false);
        hooked = hookedJdbc(() -> {
            if (switched.compareAndSet(false,true)) repository.change(installation,id,state ->
                    new McpConnectionState(withPolicy(state.connection(),McpProjectAccess.all(),Set.of(new McpAllowedTool("old","h1"))),null));
        });
        var listed = new PostgresMcpConnectionRepository(hooked,transactions).findAll(installation).stream()
                .filter(c -> c.id().equals(id)).findFirst().orElseThrow();
        assertThat(listed.projectAccess()).isEqualTo(McpProjectAccess.selected(Set.of(project)));
        assertThat(listed.allowedTools()).containsExactly(new McpAllowedTool("new","h2"));
        repository.delete(installation,id);
    }

    @Test void concurrentEndpointReplacementAndEnableKeepOneCredentialIdentity() throws Exception {
        forgeIt.postgresql().create().to(PROJECT.withJson("project_alpha.json")).build();
        UUID project = UUID.fromString("10000000-0000-4000-8000-000000000001");
        var repository = new PostgresMcpConnectionRepository(jdbc,transactions);
        var identity = new PostgresForgeInstanceIdentityRepository(jdbc);
        byte[] key = new byte[32]; Arrays.fill(key,(byte)9);
        var realCipher = new AesGcmMcpCredentialCipher(() -> new McpLocalKeys("active",Map.of("active",key)));
        CountDownLatch encrypted = new CountDownLatch(1), release = new CountDownLatch(1);
        McpCredentialCipher pausingCipher = new McpCredentialCipher() {
            public McpEncryptedCredential encrypt(UUID i,UUID c,String p,byte[] bytes) {
                if (Arrays.equals(bytes,"second".getBytes(StandardCharsets.UTF_8))) {
                    encrypted.countDown();
                    try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timed out"); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted"); }
                }
                return realCipher.encrypt(i,c,p,bytes);
            }
            public byte[] decrypt(UUID i,UUID c,String p,McpEncryptedCredential value) { return realCipher.decrypt(i,c,p,value); }
        };
        var service = new McpConnectionService(repository,projects,identity,pausingCipher);
        var created = service.create("x",URI.create("https://example.org/a"),McpAuthType.BEARER,McpProjectAccess.all(),McpCredentialSecret.bearer("first"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var update = executor.submit(() -> service.update(created.id(),"x",URI.create("https://example.org/b"),
                    McpAuthType.BEARER,McpProjectAccess.selected(Set.of(project)),McpCredentialChange.REPLACE,McpCredentialSecret.bearer("second")));
            assertThat(encrypted.await(5,TimeUnit.SECONDS)).isTrue();
            var enable = executor.submit(() -> service.setEnabled(created.id(),true));
            awaitBlockedStatement("SELECT id FROM mcp_connections");
            release.countDown();
            update.get(5,TimeUnit.SECONDS); enable.get(5,TimeUnit.SECONDS);
        } finally { release.countDown(); }
        var result = service.get(created.id());
        assertThat(result.endpoint()).isEqualTo(URI.create("https://example.org/b"));
        assertThat(result.projectAccess()).isEqualTo(McpProjectAccess.selected(Set.of(project)));
        assertThat(result.enabled()).isTrue();
        assertThat(new String(realCipher.decrypt(result.installationId(),result.id(),"credential",
                repository.credential(result.installationId(),result.id()).orElseThrow()),StandardCharsets.UTF_8)).isEqualTo("second");
        service.remove(created.id());
    }

    @Test void concurrentDeleteCannotBeUndoneByStaleMutation() throws Exception {
        var repository = new PostgresMcpConnectionRepository(jdbc,transactions);
        var identity = new PostgresForgeInstanceIdentityRepository(jdbc);
        UUID installation = identity.getOrCreate(), id = UUID.randomUUID();
        Instant now = Instant.now();
        var original = new McpConnection(id,installation,"x",URI.create("https://example.org/a"),McpAuthType.NONE,
                false,McpProjectAccess.all(),Set.of(),false,now,now,null,null);
        repository.insert(new McpConnectionState(original,null));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var changing = executor.submit(() -> repository.change(installation,id,state -> {
                entered.countDown();
                try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timed out"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted"); }
                return new McpConnectionState(withPolicy(state.connection(),McpProjectAccess.all(),Set.of()),null);
            }));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var deleting = executor.submit(() -> repository.delete(installation,id));
            awaitBlockedStatement("DELETE FROM mcp_connections");
            release.countDown();
            changing.get(5,TimeUnit.SECONDS); deleting.get(5,TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(repository.findById(installation,id)).isEmpty();
        assertThat(repository.change(installation,id,state -> state)).isEmpty();
    }

    private JdbcTemplate hookedJdbc(Runnable beforeMapping) {
        return new JdbcTemplate(jdbc.getDataSource()) {
            @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args) {
                return super.query(sql,(rs,row) -> { beforeMapping.run(); return mapper.mapRow(rs,row); },args);
            }
        };
    }

    private void awaitBlockedStatement(String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                    + "AND wait_event_type='Lock' AND query LIKE ?",Integer.class,prefix + "%");
            if (count != null && count > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Expected blocked PostgreSQL row-lock statement: " + prefix);
    }

    private static McpConnection withPolicy(McpConnection c,McpProjectAccess access,Set<McpAllowedTool> tools) {
        return new McpConnection(c.id(),c.installationId(),c.displayName(),c.endpoint(),c.authType(),c.enabled(),access,
                tools,c.credentialConfigured(),c.createdAt(),Instant.now(),c.checkedAt(),c.safeDiagnostic());
    }

    private static McpConnection connection(UUID id,UUID installation,UUID project) {
        Instant now = Instant.parse("2026-09-23T10:00:00Z");
        return new McpConnection(id,installation,"Same",URI.create("https://example.org/mcp"),McpAuthType.BEARER,
                false,McpProjectAccess.selected(Set.of(project)),Set.of(new McpAllowedTool("tool","sha256:abcd")),true,
                now,now,null,null);
    }
}
