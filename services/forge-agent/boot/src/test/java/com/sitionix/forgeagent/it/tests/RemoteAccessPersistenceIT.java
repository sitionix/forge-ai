package com.sitionix.forgeagent.it.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.infrastructure.postgres.adapter.*;
import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessProvisioningService;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class RemoteAccessPersistenceIT {
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static List<String> legacyColumns;

    @BeforeAll
    static void start() {
        DATABASE.start();
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration").target("36").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        legacyColumns = legacyColumns();
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void stop() { DATABASE.stop(); }

    @Test
    void migrationCreatesSeparateRemoteAccessTablesWithoutPrivateKeyMaterial() {
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public'", String.class))
                .contains("remote_access_invitations", "remote_access_sessions", "forge_instance_identity", "ssh_connections", "agent_execution_sessions");
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name='remote_access_sessions'", String.class);
        assertThat(columns).contains("local_private_key_reference").doesNotContain("private_key", "pairing_private_key", "pairing_token");
    }
    @Test
    void migrationDoesNotAlterLegacySshOrExecutionSessionDefinitions() {
        assertThat(legacyColumns()).isEqualTo(legacyColumns);
    }

    @Test
    void restartRetainsIdentityInvitationSessionAndIrreversibleTransitions() {
        var identity = new PostgresForgeInstanceIdentityRepository(jdbc);
        UUID local = identity.getOrCreate();
        var invitation = invitation(local, NOW.plusSeconds(120));
        var invitations = new PostgresRemoteAccessInvitationRepository(jdbc);
        invitations.insert(invitation);
        var original = grantor(invitation);
        assertThat(service(jdbc).reserveGrantorSession(original)).isEqualTo(original);

        // Dispose all adapter/connection state; reopen the same persisted database.
        var restartedJdbc = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        assertThat(new PostgresForgeInstanceIdentityRepository(restartedJdbc).getOrCreate()).isEqualTo(local);
        var restarted = new PostgresRemoteAccessSessionRepository(restartedJdbc);
        assertThat(restarted.findById(original.id())).contains(original);
        assertThat(new PostgresRemoteAccessInvitationRepository(restartedJdbc).findById(invitation.id()).orElseThrow())
                .isEqualTo(invitation.redeem(original.id(), NOW));
        var active = original.activate(NOW.plusSeconds(1));
        assertThat(restarted.transition(original, active)).isTrue();
        var revoking = active.requestRevoke(NOW.plusSeconds(2));
        assertThat(restarted.transition(active, revoking)).isTrue();
        assertThat(restarted.transition(original, active)).isFalse();
        var revoked = revoking.confirmRevoked(NOW.plusSeconds(3));
        assertThat(restarted.transition(revoking, revoked)).isTrue();
        assertThat(restarted.transition(active, revoking)).isFalse();
        assertThat(restarted.findById(original.id())).contains(revoked);
        assertThatThrownBy(() -> revoked.activate(NOW.plusSeconds(4))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void concurrentRedeemCommitsExactlyOneSession() throws Exception {
        var invitation = invitation(new PostgresForgeInstanceIdentityRepository(jdbc).getOrCreate(), NOW.plusSeconds(120));
        new PostgresRemoteAccessInvitationRepository(jdbc).insert(invitation);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var calls = java.util.stream.IntStream.range(0,2).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                try {
                    service(jdbc).reserveGrantorSession(grantor(invitation));
                    return true;
                } catch (ConflictException expected) {
                    return false;
                }
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(calls.get(0).get(10,TimeUnit.SECONDS), calls.get(1).get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remote_access_sessions WHERE invitation_id=?", Integer.class, invitation.id())).isEqualTo(1);
        UUID winner = jdbc.queryForObject("SELECT id FROM remote_access_sessions WHERE invitation_id=?", UUID.class, invitation.id());
        assertThat(new PostgresRemoteAccessInvitationRepository(jdbc).findById(invitation.id()).orElseThrow().redeemedSessionId()).isEqualTo(winner);
    }

    @Test
    void sessionInsertFailureRollsBackInvitationConsumption() {
        UUID local = new PostgresForgeInstanceIdentityRepository(jdbc).getOrCreate();
        var firstInvitation = invitation(local, NOW.plusSeconds(120));
        var invitations = new PostgresRemoteAccessInvitationRepository(jdbc);
        invitations.insert(firstInvitation);
        var existing = service(jdbc).reserveGrantorSession(grantor(firstInvitation));
        var secondInvitation = invitation(local, NOW.plusSeconds(120));
        invitations.insert(secondInvitation);
        var duplicate = new RemoteAccessSession(existing.id(), secondInvitation.id(), existing.localRole(), local,
                existing.accessorInstanceId(), existing.peerDisplayName(), existing.endpoint(),existing.pinnedHostPublicKey(),
                existing.sessionPublicKey(),existing.sessionFingerprint(),null,existing.status(),NOW,NOW.plusSeconds(60),
                null,null,null,RemoteAccessConnectivity.UNKNOWN,null,null,null,null,0);
        assertThatThrownBy(() -> service(jdbc).reserveGrantorSession(duplicate))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(invitations.findById(secondInvitation.id()).orElseThrow()).isEqualTo(secondInvitation);
        assertThat(new PostgresRemoteAccessSessionRepository(jdbc).findById(existing.id())).contains(existing);
    }

    @Test
    void expiredAndCancelledInvitationsCannotBeReserved() {
        UUID local = new PostgresForgeInstanceIdentityRepository(jdbc).getOrCreate();
        var invitations = new PostgresRemoteAccessInvitationRepository(jdbc);
        var expired = new RemoteAccessInvitation(UUID.randomUUID(),local,endpoint(),"public-pair-key","SHA256:pair",NOW.minusSeconds(60),NOW,null,null,null);
        invitations.insert(expired);
        assertThatThrownBy(() -> service(jdbc).reserveGrantorSession(grantor(expired))).isInstanceOf(ConflictException.class);
        var cancelled = invitation(local,NOW.plusSeconds(120));
        invitations.insert(cancelled);
        assertThat(invitations.cancel(cancelled.id(),NOW)).isTrue();
        assertThatThrownBy(() -> service(jdbc).reserveGrantorSession(grantor(cancelled))).isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remote_access_sessions WHERE invitation_id IN (?,?)",Integer.class,expired.id(),cancelled.id())).isZero();
    }

    @Test
    void databaseRejectsGrantorPrivateReferenceAndMissingAuthorizationState() {
        var invitation=invitation(new PostgresForgeInstanceIdentityRepository(jdbc).getOrCreate(),NOW.plusSeconds(120));
        new PostgresRemoteAccessInvitationRepository(jdbc).insert(invitation);
        var session=service(jdbc).reserveGrantorSession(grantor(invitation));
        assertThatThrownBy(() -> jdbc.update("UPDATE remote_access_sessions SET local_private_key_reference=? WHERE id=?", UUID.randomUUID(),session.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE remote_access_sessions SET status=NULL WHERE id=?",session.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void accessorCanPersistRemoteInvitationReferenceWithoutCreatingLocalInvitation() {
        UUID id=UUID.randomUUID(), invitationId=UUID.randomUUID();
        var session=new RemoteAccessSession(id,invitationId,RemoteAccessRole.ACCESSOR,UUID.randomUUID(),UUID.randomUUID(),
                "Grantor",endpoint(),"public-host","public-session","SHA256:session",id,RemoteAccessSessionStatus.PROVISIONING,
                NOW,NOW.plusSeconds(60),null,null,null,RemoteAccessConnectivity.UNKNOWN,null,null,null,null,0);
        var repository=new PostgresRemoteAccessSessionRepository(jdbc);
        repository.insert(session);
        assertThat(repository.findById(id)).contains(session);
        assertThat(new PostgresRemoteAccessInvitationRepository(jdbc).findById(invitationId)).isEmpty();
    }

    @Test
    void accessorPersistsKeyReferenceAcrossRestartAndCleansOnlyNewKeyOnDatabaseFailure(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        UUID local = new PostgresForgeInstanceIdentityRepository(jdbc).getOrCreate();
        UUID id = UUID.randomUUID(), invitationId = UUID.randomUUID();
        var candidate = accessor(id,invitationId,local);
        var root = directory.resolve("credentials");
        var store = new com.sitionix.forgeagent.infrastructure.local.LocalRemoteAccessCredentialStore(root);
        var provisioning = new RemoteAccessProvisioningService(new PostgresRemoteAccessInvitationRepository(jdbc),
                new PostgresRemoteAccessSessionRepository(jdbc),new PostgresForgeInstanceIdentityRepository(jdbc),store,
                Clock.fixed(NOW,ZoneOffset.UTC),new DataSourceTransactionManager(jdbc.getDataSource()));
        byte[] synthetic = "synthetic-only-private-material".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UUID losingId = UUID.randomUUID();
        try (var key = new RemoteAccessPrivateKey(synthetic)) {
            assertThat(provisioning.createAccessorSession(candidate,key)).isEqualTo(candidate);
            assertThatThrownBy(() -> provisioning.createAccessorSession(accessor(losingId,invitationId,local),key))
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        }
        var restarted = new PostgresRemoteAccessSessionRepository(new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword())));
        var stored = restarted.findById(id).orElseThrow();
        assertThat(stored).isEqualTo(candidate);
        assertThat(restarted.findById(losingId)).isEmpty();
        assertThat(root.resolve(losingId.toString())).doesNotExist();
        try (var restored = new com.sitionix.forgeagent.infrastructure.local.LocalRemoteAccessCredentialStore(root)
                .read(stored.localPrivateKeyReference())) {
            assertThat(restored.copyBytes()).isEqualTo(synthetic);
        }
    }

    private static RemoteAccessSession accessor(UUID id, UUID invitationId, UUID local) {
        return new RemoteAccessSession(id,invitationId,RemoteAccessRole.ACCESSOR,UUID.randomUUID(),local,
                "Grantor",endpoint(),"public-host","public-session","SHA256:session",id,RemoteAccessSessionStatus.PROVISIONING,
                NOW,NOW.plusSeconds(60),null,null,null,RemoteAccessConnectivity.UNKNOWN,null,null,null,null,0);
    }

    @Test
    void accessorRejectsAmbientTransactionBeforeWritingAnyCredentials(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        UUID local = new PostgresForgeInstanceIdentityRepository(jdbc).getOrCreate();
        UUID id = UUID.randomUUID();
        var candidate = new RemoteAccessSession(id,UUID.randomUUID(),RemoteAccessRole.ACCESSOR,UUID.randomUUID(),local,
                "Grantor",endpoint(),"public-host","public-session","SHA256:session",id,RemoteAccessSessionStatus.PROVISIONING,
                NOW,NOW.plusSeconds(60),null,null,null,RemoteAccessConnectivity.UNKNOWN,null,null,null,null,0);
        var store = new com.sitionix.forgeagent.infrastructure.local.LocalRemoteAccessCredentialStore(directory.resolve("credentials"));
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var provisioning = new RemoteAccessProvisioningService(new PostgresRemoteAccessInvitationRepository(jdbc),
                new PostgresRemoteAccessSessionRepository(jdbc),new PostgresForgeInstanceIdentityRepository(jdbc),store,
                Clock.fixed(NOW,ZoneOffset.UTC),manager);
        try (var key = new RemoteAccessPrivateKey("synthetic-private-key".getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(transaction -> {
                assertThatThrownBy(() -> provisioning.createAccessorSession(candidate,key))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Remote access provisioning must own its transaction boundary");
                transaction.setRollbackOnly();
            });
        }
        assertThat(new PostgresRemoteAccessSessionRepository(jdbc).findById(id)).isEmpty();
        assertThat(directory.resolve("credentials")).doesNotExist();
    }

    @Test
    void fullApplicationContextRestartKeepsPersistedInstanceAndGrantorSession() {
        UUID local;
        RemoteAccessSession original;
        try (var context = startApplication()) {
            local = context.getBean(ForgeInstanceIdentityRepository.class).getOrCreate();
            Instant now = context.getBean(Clock.class).instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var invitation = new RemoteAccessInvitation(UUID.randomUUID(),local,endpoint(),"public-pair-key","SHA256:pair",now,now.plusSeconds(300),null,null,null);
            context.getBean(RemoteAccessInvitationRepository.class).insert(invitation);
            original = new RemoteAccessSession(UUID.randomUUID(),invitation.id(),RemoteAccessRole.GRANTOR,local,UUID.randomUUID(),
                    "Accessor",endpoint(),"public-host","public-session","SHA256:session",null,RemoteAccessSessionStatus.PROVISIONING,
                    now,now.plusSeconds(300),null,null,null,RemoteAccessConnectivity.UNKNOWN,null,null,null,null,0);
            context.getBean(RemoteAccessProvisioningService.class).reserveGrantorSession(original);
        }
        try (var restarted = startApplication()) {
            assertThat(restarted.getBean(ForgeInstanceIdentityRepository.class).getOrCreate()).isEqualTo(local);
            assertThat(restarted.getBean(RemoteAccessSessionRepository.class).findById(original.id())).contains(original);
            assertThat(restarted.getBean(RemoteAccessInvitationRepository.class).findById(original.invitationId()).orElseThrow().redeemedSessionId())
                    .isEqualTo(original.id());
        }
    }

    private static org.springframework.context.ConfigurableApplicationContext startApplication() {
        return new org.springframework.boot.builder.SpringApplicationBuilder(com.sitionix.forgeagent.ForgeAgentApplication.class)
                .run("--server.port=0", "--spring.datasource.url="+DATABASE.getJdbcUrl(),
                        "--spring.datasource.username="+DATABASE.getUsername(), "--spring.datasource.password="+DATABASE.getPassword(),
                        "--forge.agent.worker.scheduling-enabled=false", "--spring.main.banner-mode=off");
    }

    private static RemoteAccessProvisioningService service(JdbcTemplate template) {
        RemoteAccessCredentialStore unused = new RemoteAccessCredentialStore() {
            public UUID store(UUID id,RemoteAccessPrivateKey key) { throw new AssertionError("Grantor must not store private keys"); }
            public RemoteAccessPrivateKey read(UUID id) { throw new AssertionError("Grantor must not read private keys"); }
            public void delete(UUID id) { throw new AssertionError("Grantor must not delete private keys"); }
        };
        return new RemoteAccessProvisioningService(new PostgresRemoteAccessInvitationRepository(template),
                new PostgresRemoteAccessSessionRepository(template),new PostgresForgeInstanceIdentityRepository(template),unused,
                Clock.fixed(NOW,ZoneOffset.UTC),new DataSourceTransactionManager(template.getDataSource()));
    }

    private static RemoteAccessInvitation invitation(UUID local,Instant expires) {
        return new RemoteAccessInvitation(UUID.randomUUID(),local,endpoint(),"public-pair-key","SHA256:pair",NOW,expires,null,null,null);
    }
    private static RemoteAccessEndpoint endpoint() { return new RemoteAccessEndpoint("localhost",2222,"forge"); }
    private static RemoteAccessSession grantor(RemoteAccessInvitation invitation) {
        return new RemoteAccessSession(UUID.randomUUID(),invitation.id(),RemoteAccessRole.GRANTOR,invitation.grantorInstanceId(),UUID.randomUUID(),
                "Accessor",endpoint(),"public-host","public-session","SHA256:session",null,RemoteAccessSessionStatus.PROVISIONING,
                NOW,NOW.plusSeconds(60),null,null,null,RemoteAccessConnectivity.UNKNOWN,null,null,null,null,0);
    }
    private static List<String> legacyColumns() {
        return jdbc.queryForList("SELECT table_name||'.'||column_name||':'||data_type||':'||is_nullable||':'||coalesce(column_default,'') FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('ssh_connections','agent_execution_sessions') ORDER BY table_name,ordinal_position",String.class);
    }

}
