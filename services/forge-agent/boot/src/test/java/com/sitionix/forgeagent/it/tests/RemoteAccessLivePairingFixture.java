package com.sitionix.forgeagent.it.tests;

import static org.assertj.core.api.Assertions.*;
import com.sitionix.forgeagent.application.remoteaccess.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.infrastructure.local.LocalRemoteAccessCredentialStore;
import com.sitionix.forgeagent.infrastructure.local.LocalRemoteAccessReverseInvitationStore;
import com.sitionix.forgeagent.infrastructure.local.remoteaccess.*;
import com.sitionix.forgeagent.infrastructure.postgres.adapter.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Runs as the real forge-control UID inside the disposable SSH container.
 * Directly wires production services/adapters; no fake authority, fake persistence, or successful transport stub.
 * Recovery checks retain PostgreSQL/key files across reconstructed services and abrupt child-JVM exits.
 */
public final class RemoteAccessLivePairingFixture {
    private static final Clock CLOCK=Clock.systemUTC();
    private static final Path STATE=Path.of("/fixture/state");
    private static final LocalPairingTokens TOKENS=new LocalPairingTokens();
    private static final LocalInvitationGrants GRANTS=new LocalInvitationGrants();
    // This legacy Stage 4 fixture verifies SSH/persistence only. Stage 5 live execution
    // uses the real supervisor and checks workspace preparation before ACTIVE.
    private static final RemoteAccessWorkloads TRANSPORT_ONLY_WORKLOADS=new RemoteAccessWorkloads() {
        public void reconcile(UUID epoch) { }
        public void heartbeat(UUID epoch) { }
        public void prepare(UUID session) { }
        public void start(UUID session,UUID attachment,UUID epoch) { }
        public void stop(UUID session) { }
    };
    private static final RemoteAccessEndpoint ENDPOINT=new RemoteAccessEndpoint("127.0.0.1",22222,"forge-ssh");
    private static String url,user,password;

    public static void main(String[] args) throws Exception {
        if (args.length == 4) {
            url=args[1];user=args[2];password=args[3];
            runChild(args[0]);
            return;
        }
        url=args[0];user=args[1];password=args[2];
        for(String schema:new String[]{"peer_a","peer_b","peer_c"}) {
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load().migrate();
        }
        assertThat(System.getProperty("user.name")).isEqualTo("forge-control");
        happyAndWrongCredentials();
        lostAcknowledgementThenRestart();
        reservationWithoutInstalledKeyThenRestart();
        grantorJvmCrashBeforeInstallation();
        accessorJvmCrashAfterConfirmation();
        concurrentRedeemAcrossIndependentAccessors();
        expiredProvisioningRemovesGrantAndRetainsUnconfirmedAccessorKey();
        mutualPairingAcrossTwoPersistedForgeIdentities();
    }

    private static void mutualPairingAcrossTwoPersistedForgeIdentities() throws Exception {
        var a=new Peer("peer_a");var b=new Peer("peer_b");
        var aGrantor=a.grantor(GRANTS);var bGrantor=b.grantor(GRANTS);
        RemoteAccessPeerPairing router=new RemoteAccessPeerPairing() {
            public UUID redeem(RemoteAccessInvitationBinding binding,RemoteAccessPairingRequest request) {
                return (binding.grantorInstanceId().equals(a.local())?aGrantor:bGrantor).redeem(binding,request);
            }
            public java.util.Optional<RemoteAccessSessionStatus> confirm(RemoteAccessKeyBinding binding) {
                return (binding.grantorInstanceId().equals(a.local())?aGrantor:bGrantor).confirm(binding);
            }
            public java.util.Optional<UUID> reverse(RemoteAccessKeyBinding binding,RemoteAccessReverseRequest request) {
                return (binding.grantorInstanceId().equals(a.local())?aGrantor:bGrantor).reverse(binding,request);
            }
        };
        var aAuthority=new RemoteAccessChannelService(a.sessions,a.identity,CLOCK,a.invitations);
        var bAuthority=new RemoteAccessChannelService(b.sessions,b.identity,CLOCK,b.invitations);
        RemoteAccessChannelAuthority authority=new RemoteAccessChannelAuthority() {
            public java.util.Optional<RemoteAccessSessionStatus> sessionStatus(RemoteAccessKeyBinding binding) {
                return (binding.grantorInstanceId().equals(a.local())?aAuthority:bAuthority).sessionStatus(binding);
            }
            public boolean pairingAllowed(RemoteAccessInvitationBinding binding) {
                return (binding.grantorInstanceId().equals(a.local())?aAuthority:bAuthority).pairingAllowed(binding);
            }
        };
        RemoteAccessPeerExecution noExecution=new RemoteAccessPeerExecution() {
            public void start(RemoteAccessKeyBinding binding,UUID attachment) { throw new IllegalStateException("No fixture workload"); }
            public RemoteAccessSessionStatus revoke(RemoteAccessKeyBinding binding) { throw new IllegalStateException("No fixture workload"); }
        };
        RemoteAccessSetup bSetup=new RemoteAccessSetup() {
            public RemoteAccessCapabilities capabilities() { return new RemoteAccessCapabilities(true,java.util.List.of("CONNECT"),java.util.List.of()); }
            public RemoteAccessEndpoint advertisedEndpoint(String host) { return ENDPOINT; }
            public RemoteAccessEndpoint advertisedEndpointForPeer(RemoteAccessEndpoint peer) { return ENDPOINT; }
            public String displayName() { return "Forge B"; }
        };
        var bSecrets=new LocalRemoteAccessReverseInvitationStore(b.credentialsRoot.toString());
        var bManagement=new RemoteAccessManagement(b.sessions,b.identity,b.transport(),noExecution,
                new RemoteAccessAccessorExecution(b.sessions,b.identity,b.transport(),b.credentials,
                        new LocalRemoteAccessCommandTransport(b.credentials),b.access,CLOCK),CLOCK,b.pairs,b.inviter());
        var mutual=new RemoteAccessMutualPairing(b.accessor(b.transport()),b.inviter(),bSetup,TOKENS,b.identity,b.pairs,b.sessions,
                bSecrets,b.transport(),CLOCK,bManagement);
        var invitation=a.inviter().create(ENDPOINT,"Forge A");
        try(var server=new RemoteAccessChannelServer(Path.of("/run/forge-remote/channel/authority.sock"),"forge-ssh","forge-ssh",
                authority,router,noExecution)) {
            server.start();
            var forward=mutual.connect(invitation.token().value(),"Forge B");
            assertThat(mutual.connected(forward.id())).isTrue();
            var pairA=a.pairs.findById(invitation.invitation().id()).orElseThrow();
            var pairB=b.pairs.findById(invitation.invitation().id()).orElseThrow();
            assertThat(pairA.forwardSessionId()).isEqualTo(pairB.forwardSessionId());
            assertThat(pairA.reverseSessionId()).isEqualTo(pairB.reverseSessionId()).isNotEqualTo(forward.id());
            assertThat(a.sessions.findById(pairA.forwardSessionId()).orElseThrow().localRole()).isEqualTo(RemoteAccessRole.GRANTOR);
            assertThat(b.sessions.findById(pairA.forwardSessionId()).orElseThrow().localRole()).isEqualTo(RemoteAccessRole.ACCESSOR);
            assertThat(a.sessions.findById(pairA.reverseSessionId()).orElseThrow().localRole()).isEqualTo(RemoteAccessRole.ACCESSOR);
            assertThat(b.sessions.findById(pairA.reverseSessionId()).orElseThrow().localRole()).isEqualTo(RemoteAccessRole.GRANTOR);
            for (var sessionId:java.util.List.of(pairA.forwardSessionId(),pairA.reverseSessionId())) {
                assertThat(a.sessions.findById(sessionId).orElseThrow().status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
                assertThat(b.sessions.findById(sessionId).orElseThrow().status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
            }
            assertThatThrownBy(() -> bSecrets.read(pairB.id())).isInstanceOf(RuntimeException.class);
            System.out.println("PASS one human token created two independently ACTIVE SSH directions across persisted Forge identities");
        }
    }

    private static void happyAndWrongCredentials() throws Exception {
        var a=new Peer("peer_a");var b=new Peer("peer_b");
        assertThat(a.local()).isNotEqualTo(b.local());
        var invitation=b.inviter().create(ENDPOINT,"Grantor B");
        try(var server=b.server(GRANTS)) {
            server.start();
            var session=a.accessor(a.transport()).connect(invitation.token().value(),"Accessor A");
            activeBoth(a,b,session.id());
            assertThat(session.peerDisplayName()).isEqualTo("Grantor B");
            assertThat(b.sessions.findById(session.id()).orElseThrow().peerDisplayName()).isEqualTo("Accessor A");
            assertThat(a.sessions.findLocal(a.local()).stream().filter(value -> value.invitationId().equals(invitation.invitation().id())).count()).isEqualTo(1);
            assertThat(a.accessor(a.transport()).connect(invitation.token().value(),"Accessor A").id()).isEqualTo(session.id());
            try(var decoded=TOKENS.decode(invitation.token().value(),a.local())) {
                assertThatThrownBy(() -> a.transport().redeem(session,decoded.privateKey(),"Accessor A")).isInstanceOf(IllegalStateException.class);
            }
            var wrongStore=new LocalRemoteAccessCredentialStore(STATE.resolve("wrong-key"));
            try(var wrong=TOKENS.generate()) { wrongStore.store(session.localPrivateKeyReference(),wrong.privateKey()); }
            assertThatThrownBy(() -> new LocalRemoteAccessPairingTransport(wrongStore).confirm(session)).isInstanceOf(IllegalStateException.class);
            activeBoth(a,b,session.id());
            System.out.println("PASS persisted two-peer ACTIVE");
            System.out.println("PASS consumed invitation and wrong session key denied");
        }
    }

    private static void lostAcknowledgementThenRestart() throws Exception {
        var a=new Peer("peer_a");var b=new Peer("peer_b");
        var invitation=b.inviter().create(ENDPOINT,"Grantor B");
        UUID id;
        try(var server=b.server(GRANTS)) {
            server.start();
            var actual=a.transport();
            // Both RPCs genuinely cross SSH. Drop the successful responses before the caller can persist ACTIVE.
            RemoteAccessPairingTransport loseResponses=new RemoteAccessPairingTransport() {
                public void redeem(RemoteAccessSession session,RemoteAccessPrivateKey key,String name) {
                    actual.redeem(session,key,name); throw new IllegalStateException("Injected lost redeem response");
                }
                public RemoteAccessSessionStatus confirm(RemoteAccessSession session) {
                    actual.confirm(session); throw new IllegalStateException("Injected lost confirm acknowledgement");
                }
                public RemoteAccessSessionStatus status(RemoteAccessSession session) { return actual.status(session); }
            };
            var attempt=a.accessor(loseResponses).connect(invitation.token().value(),"Accessor A");
            id=attempt.id();
            assertThat(attempt.status()).isEqualTo(RemoteAccessSessionStatus.PROVISIONING);
            assertThat(b.sessions.findById(id).orElseThrow().status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
        }
        var restartedA=new Peer("peer_a");var restartedB=new Peer("peer_b");
        assertThat(restartedA.local()).isEqualTo(a.local());
        assertThat(restartedB.local()).isEqualTo(b.local());
        try(var server=restartedB.server(GRANTS)) {
            server.start();
            assertThat(restartedA.accessor(restartedA.transport()).resume(id).status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
            activeBoth(restartedA,restartedB,id);
            System.out.println("PASS lost acknowledgement restart recovery");
        }
    }

    private static void reservationWithoutInstalledKeyThenRestart() throws Exception {
        var a=new Peer("peer_a");var b=new Peer("peer_b");
        var invitation=b.inviter().create(ENDPOINT,"Grantor B");
        var failedInstall=new AtomicBoolean();
        RemoteAccessSessionGrants failureBeforeInstall=new RemoteAccessSessionGrants() {
            public void install(RemoteAccessSession session) { failedInstall.set(true); throw new IllegalStateException("Injected supervisor outage"); }
            public void remove(RemoteAccessSession session) { GRANTS.remove(session); }
        };
        UUID id;
        try(var server=b.server(failureBeforeInstall)) {
            server.start();
            var attempt=a.accessor(a.transport()).connect(invitation.token().value(),"Accessor A");
            id=attempt.id();
            assertThat(failedInstall.get()).isTrue();
            assertThat(attempt.status()).isEqualTo(RemoteAccessSessionStatus.PROVISIONING);
            assertThat(b.sessions.findById(id).orElseThrow().status()).isEqualTo(RemoteAccessSessionStatus.PROVISIONING);
            assertThat(b.invitations.findById(invitation.invitation().id()).orElseThrow().redeemedSessionId()).isEqualTo(id);
            try(var decoded=TOKENS.decode(invitation.token().value(),a.local())) {
                assertThatThrownBy(() -> a.transport().redeem(attempt,decoded.privateKey(),"Accessor A")).isInstanceOf(IllegalStateException.class);
            }
        }
        var restartedA=new Peer("peer_a");var restartedB=new Peer("peer_b");
        restartedB.grantor(GRANTS).reconcile();
        restartedB.inviter().cleanupUnavailable();
        try(var server=restartedB.server(GRANTS)) {
            server.start();
            assertThat(restartedA.accessor(restartedA.transport()).resume(id).status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
            activeBoth(restartedA,restartedB,id);
            assertThat(restartedA.sessions.findByInvitation(invitation.invitation().id()).orElseThrow().id()).isEqualTo(id);
            System.out.println("PASS reservation before install restart recovery");
        }
    }

    private static void grantorJvmCrashBeforeInstallation() throws Exception {
        var a = new Peer("peer_a");
        var b = new Peer("peer_b");
        var invitation = b.inviter().create(ENDPOINT, "Grantor B");
        RemoteAccessSession attempt;
        try (var child = new GrantorProcess("grantor-crash-before-install")) {
            attempt = a.accessor(a.transport()).connect(invitation.token().value(), "Accessor A");
            assertThat(child.process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(child.process.exitValue()).isEqualTo(77);
            assertThat(attempt.status()).isEqualTo(RemoteAccessSessionStatus.PROVISIONING);
            assertThat(b.sessions.findById(attempt.id()).orElseThrow().status()).isEqualTo(RemoteAccessSessionStatus.PROVISIONING);
            assertThat(Path.of("/run/forge-remote/channel/authority.sock")).exists();
        }
        // The prior JVM skipped every shutdown hook. The replacement must recover its stale socket itself.
        try (var restarted = new GrantorProcess("grantor-recover")) {
            var restartedA = new Peer("peer_a");
            assertThat(restartedA.accessor(restartedA.transport()).resume(attempt.id()).status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
            activeBoth(restartedA, new Peer("peer_b"), attempt.id());
            System.out.println("PASS grantor JVM crash before install recovered");
        }
    }

    private static void accessorJvmCrashAfterConfirmation() throws Exception {
        var a = new Peer("peer_a");
        var b = new Peer("peer_b");
        var invitation = b.inviter().create(ENDPOINT, "Grantor B");
        try (var server = b.server(GRANTS)) {
            server.start();
            Process child = startChild("accessor-crash-after-confirm");
            try {
                // Synthetic invitation travels over stdin, never process arguments, logs or a token file.
                try (var input = child.getOutputStream()) {
                    input.write((invitation.token().value()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                assertThat(child.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(child.exitValue()).withFailMessage("Accessor child failed: %s", childLog()).isEqualTo(78);
                var pending = a.sessions.findByInvitation(invitation.invitation().id()).orElseThrow();
                assertThat(pending.status()).isEqualTo(RemoteAccessSessionStatus.PROVISIONING);
                assertThat(b.sessions.findById(pending.id()).orElseThrow().status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
                var restartedA = new Peer("peer_a");
                assertThat(restartedA.accessor(restartedA.transport()).resume(pending.id()).status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
                activeBoth(restartedA, b, pending.id());
                System.out.println("PASS accessor JVM crash after confirmation recovered");
            } finally {
                stopChild(child);
            }
        }
    }

    private static void runChild(String mode) throws Exception {
        if (mode.equals("accessor-crash-after-confirm")) {
            String token = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8)).readLine();
            var a = new Peer("peer_a");
            var actual = a.transport();
            RemoteAccessPairingTransport interrupted = new RemoteAccessPairingTransport() {
                public void redeem(RemoteAccessSession session, RemoteAccessPrivateKey key, String name) { actual.redeem(session,key,name); }
                public RemoteAccessSessionStatus confirm(RemoteAccessSession session) {
                    actual.confirm(session);
                    Runtime.getRuntime().halt(78);
                    throw new AssertionError("halt returned");
                }
                public RemoteAccessSessionStatus status(RemoteAccessSession session) { return actual.status(session); }
            };
            a.accessor(interrupted).connect(token, "Accessor A");
            throw new AssertionError("Accessor did not reach confirmed activation");
        }
        var b = new Peer("peer_b");
        RemoteAccessSessionGrants grants = mode.equals("grantor-crash-before-install") ? new RemoteAccessSessionGrants() {
            public void install(RemoteAccessSession session) { Runtime.getRuntime().halt(77); }
            public void remove(RemoteAccessSession session) { GRANTS.remove(session); }
        } : GRANTS;
        var server = b.server(grants);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        if (mode.equals("grantor-recover")) b.grantor(GRANTS).reconcile();
        Files.writeString(STATE.resolve("grantor-ready"), "ready");
        new java.util.concurrent.CountDownLatch(1).await();
    }

    private static Process startChild(String mode) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), RemoteAccessLivePairingFixture.class.getName(),
                mode, url, user, password).redirectErrorStream(true)
                .redirectOutput(STATE.resolve("child.log").toFile()).start();
    }

    private static String childLog() throws Exception { return Files.readString(STATE.resolve("child.log")); }
    private static void stopChild(Process process) throws Exception {
        if (process.isAlive()) process.destroy();
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertThat(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    private static final class GrantorProcess implements AutoCloseable {
        final Process process;
        GrantorProcess(String mode) throws Exception {
            Path ready = STATE.resolve("grantor-ready");
            Files.deleteIfExists(ready);
            process = startChild(mode);
            boolean started = false;
            try {
                for (int index=0; index<200 && process.isAlive(); index++) {
                    if (Files.exists(ready)) { started=true; break; }
                    Thread.sleep(50);
                }
                assertThat(started).withFailMessage("Grantor child failed to start: %s", childLog()).isTrue();
            } finally {
                if (!started) stopChild(process);
            }
        }
        @Override public void close() throws Exception { stopChild(process); }
    }

    private static void concurrentRedeemAcrossIndependentAccessors() throws Exception {
        var a=new Peer("peer_a");var c=new Peer("peer_c");var b=new Peer("peer_b");
        var invitation=b.inviter().create(ENDPOINT,"Grantor B");
        try(var server=b.server(GRANTS);var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            server.start();
            var ready=new java.util.concurrent.CountDownLatch(2);
            var start=new java.util.concurrent.CountDownLatch(1);
            var first=workers.submit(() -> { ready.countDown();if(!start.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent start timed out");return a.accessor(a.transport()).connect(invitation.token().value(),"Accessor A"); });
            var second=workers.submit(() -> { ready.countDown();if(!start.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent start timed out");return c.accessor(c.transport()).connect(invitation.token().value(),"Accessor C"); });
            assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();
            var attempts=java.util.List.of(first.get(40,java.util.concurrent.TimeUnit.SECONDS),second.get(40,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(attempts.stream().filter(value -> value.status()==RemoteAccessSessionStatus.ACTIVE).count()).isEqualTo(1);
            assertThat(b.jdbc.queryForObject("SELECT count(*) FROM remote_access_sessions WHERE invitation_id=?",Integer.class,invitation.invitation().id())).isEqualTo(1);
            var accepted=b.sessions.findByInvitation(invitation.invitation().id()).orElseThrow();
            assertThat(accepted.status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
            var loser=attempts.stream().filter(value -> !value.id().equals(accepted.id())).findFirst().orElseThrow();
            assertThat(loser.status()).isEqualTo(RemoteAccessSessionStatus.PROVISIONING);
            assertThat(b.sessions.findById(loser.id())).isEmpty();
            System.out.println("PASS concurrent SSH redemption exactly one grantor session");
        }
    }

    private static void expiredProvisioningRemovesGrantAndRetainsUnconfirmedAccessorKey() throws Exception {
        var a=new Peer("peer_a");var b=new Peer("peer_b");
        var invitation=b.inviter().create(ENDPOINT,"Grantor B");
        RemoteAccessSession attempt;
        try(var server=b.server(GRANTS)) {
            server.start();
            var actual=a.transport();
            RemoteAccessPairingTransport interruptBeforeConfirmation=new RemoteAccessPairingTransport() {
                public void redeem(RemoteAccessSession session,RemoteAccessPrivateKey key,String name) { actual.redeem(session,key,name); }
                public RemoteAccessSessionStatus confirm(RemoteAccessSession session) { throw new IllegalStateException("Injected accessor interruption before confirmation"); }
                public RemoteAccessSessionStatus status(RemoteAccessSession session) { return actual.status(session); }
            };
            attempt=a.accessor(interruptBeforeConfirmation).connect(invitation.token().value(),"Accessor A");
            assertThat(actual.status(attempt)).isEqualTo(RemoteAccessSessionStatus.PROVISIONING);
            assertThat(Files.readString(Path.of("/var/lib/forge-remote/transport-home/.ssh/authorized_keys"))).contains(attempt.sessionPublicKey());
        }
        var grantorDeadline=b.sessions.findById(attempt.id()).orElseThrow().provisioningExpiresAt();
        var lastDeadline=attempt.provisioningExpiresAt().isAfter(grantorDeadline)?attempt.provisioningExpiresAt():grantorDeadline;
        Clock expired=Clock.fixed(lastDeadline.plusSeconds(1),java.time.ZoneOffset.UTC);
        var restartedB=new Peer("peer_b",expired);var restartedA=new Peer("peer_a",expired);
        restartedB.grantor(GRANTS).reconcile();
        assertThat(restartedB.sessions.findById(attempt.id()).orElseThrow().status()).isEqualTo(RemoteAccessSessionStatus.REVOKED);
        assertThat(Files.readString(Path.of("/var/lib/forge-remote/transport-home/.ssh/authorized_keys"))).doesNotContain(attempt.sessionPublicKey());
        try(var server=restartedB.server(GRANTS)) {
            server.start();
            assertThatThrownBy(() -> restartedA.transport().status(attempt)).isInstanceOf(IllegalStateException.class);
            var retained=restartedA.accessor(restartedA.transport()).resume(attempt.id());
            assertThat(retained.status()).isEqualTo(RemoteAccessSessionStatus.REVOKING);
            assertThat(retained.localPrivateKeyReference()).isNotNull();
            assertThat(restartedA.credentialsRoot.resolve(retained.localPrivateKeyReference().toString())).isRegularFile();
            System.out.println("PASS expired grant removed and unconfirmed accessor credential retained");
        }
    }

    private static void activeBoth(Peer a,Peer b,UUID id) throws Exception {
        var accessor=a.sessions.findById(id).orElseThrow();var grantor=b.sessions.findById(id).orElseThrow();
        assertThat(accessor.status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
        assertThat(grantor.status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
        assertThat(accessor.localRole()).isEqualTo(RemoteAccessRole.ACCESSOR);
        assertThat(grantor.localRole()).isEqualTo(RemoteAccessRole.GRANTOR);
        assertThat(accessor.sessionPublicKey()).isEqualTo(grantor.sessionPublicKey());
        assertThat(grantor.localPrivateKeyReference()).isNull();
        assertThat(b.credentialsRoot).doesNotExist();
        assertThat(a.credentialsRoot.resolve(accessor.localPrivateKeyReference().toString())).isRegularFile();
        assertThat(a.transport().status(accessor)).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
        assertThat(a.jdbc.queryForObject("SELECT count(*) FROM remote_access_sessions WHERE invitation_id=?",Integer.class,accessor.invitationId())).isEqualTo(1);
        assertThat(b.jdbc.queryForObject("SELECT count(*) FROM remote_access_sessions WHERE invitation_id=?",Integer.class,accessor.invitationId())).isEqualTo(1);
        assertThat(a.jdbc.queryForObject("SELECT count(*) FROM remote_access_invitations WHERE id=?",Integer.class,accessor.invitationId())).isZero();
    }

    private static final class Peer {
        final JdbcTemplate jdbc;
        final PostgresForgeInstanceIdentityRepository identity;
        final PostgresRemoteAccessInvitationRepository invitations;
        final PostgresRemoteAccessSessionRepository sessions;
        final PostgresRemoteAccessPairRepository pairs;
        final LocalRemoteAccessCredentialStore credentials;
        final Path credentialsRoot;
        final DataSourceTransactionManager transactions;
        final RemoteAccessSwitch access;
        final Clock clock;
        Peer(String schema) { this(schema,CLOCK); }
        Peer(String schema,Clock clock) {
            this.clock=clock;
            var source=new DriverManagerDataSource(url+"?currentSchema="+schema,user,password);
            jdbc=new JdbcTemplate(source);transactions=new DataSourceTransactionManager(source);
            identity=new PostgresForgeInstanceIdentityRepository(jdbc);
            invitations=new PostgresRemoteAccessInvitationRepository(jdbc);
            sessions=new PostgresRemoteAccessSessionRepository(jdbc);
            pairs=new PostgresRemoteAccessPairRepository(jdbc);
            access=new RemoteAccessSwitch(new PostgresRemoteAccessSwitchRepository(jdbc));
            access.enable();
            credentialsRoot=STATE.resolve(schema+"-credentials");
            credentials=new LocalRemoteAccessCredentialStore(credentialsRoot);
        }
        UUID local() { return identity.getOrCreate(); }
        RemoteAccessProvisioningService provisioning() { return new RemoteAccessProvisioningService(invitations,sessions,identity,credentials,clock,transactions); }
        RemoteAccessInvitations inviter() { return new RemoteAccessInvitations(invitations,identity,TOKENS,GRANTS,access,clock,transactions); }
        RemoteAccessGrantorPairing grantor(RemoteAccessSessionGrants grants) { return new RemoteAccessGrantorPairing(invitations,sessions,identity,TOKENS,GRANTS,grants,provisioning(),TRANSPORT_ONLY_WORKLOADS,access,clock,pairs,accessor(transport())); }
        LocalRemoteAccessPairingTransport transport() { return new LocalRemoteAccessPairingTransport(credentials); }
        RemoteAccessAccessorPairing accessor(RemoteAccessPairingTransport transport) { return new RemoteAccessAccessorPairing(sessions,identity,TOKENS,provisioning(),transport,access,clock); }
        RemoteAccessChannelServer server(RemoteAccessSessionGrants grants) {
            return new RemoteAccessChannelServer(Path.of("/run/forge-remote/channel/authority.sock"),"forge-ssh","forge-ssh",
                    new RemoteAccessChannelService(sessions,identity,clock,invitations),grantor(grants),
                    new com.sitionix.forgeagent.domain.port.RemoteAccessPeerExecution() {
                        public void start(RemoteAccessKeyBinding binding,UUID attachment) { throw new IllegalStateException("Stage 4 fixture has no workloads"); }
                        public RemoteAccessSessionStatus revoke(RemoteAccessKeyBinding binding) { throw new IllegalStateException("Stage 4 fixture has no workloads"); }
                    });
        }
    }
}
