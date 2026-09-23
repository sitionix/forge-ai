package com.sitionix.forgeagent.application.remoteaccess;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RemoteAccessExecutionServiceTest {
    static final UUID LOCAL=UUID.randomUUID(), PEER=UUID.randomUUID(), ID=UUID.randomUUID();
    static final Instant NOW=Instant.parse("2026-09-23T12:00:00Z");
    static final String FP="SHA256:"+"A".repeat(43);
    @Mock RemoteAccessSessionRepository sessions;
    @Mock ForgeInstanceIdentityRepository identity;
    @Mock RemoteAccessWorkloads workloads;
    @Mock RemoteAccessSessionGrants grants;
    AtomicReference<RemoteAccessSession> row;
    RemoteAccessExecutionService sut;

    @BeforeEach void setup() {
        row=new AtomicReference<>(session().activate(NOW));
        lenient().when(identity.getOrCreate()).thenReturn(LOCAL);
        lenient().when(sessions.findById(ID)).thenAnswer(call -> Optional.of(row.get()));
        lenient().when(sessions.findLocal(LOCAL)).thenAnswer(call -> List.of(row.get()));
        lenient().when(sessions.transition(any(),any())).thenAnswer(call -> row.compareAndSet(call.getArgument(0),call.getArgument(1)));
        lenient().when(sessions.recordFailure(any(),nullable(String.class),nullable(String.class))).thenAnswer(call -> {
            RemoteAccessSession before=call.getArgument(0);
            return row.compareAndSet(before,before.withFailure(call.getArgument(1),call.getArgument(2)));
        });
        sut=new RemoteAccessExecutionService(sessions,identity,workloads,grants,Clock.fixed(NOW,ZoneOffset.UTC));
    }
    static RemoteAccessSession session() {
        return new RemoteAccessSession(ID,UUID.randomUUID(),RemoteAccessRole.GRANTOR,LOCAL,PEER,"peer",
            new RemoteAccessEndpoint("127.0.0.1",2222,"forge-ssh"),"host","key",FP,null,
            RemoteAccessSessionStatus.PROVISIONING,NOW.minusSeconds(60),NOW.plusSeconds(240),null,null,null,
            RemoteAccessConnectivity.UNKNOWN,null,null,null,null,0);
    }
    static RemoteAccessKeyBinding binding() { return new RemoteAccessKeyBinding(LOCAL,ID,FP); }

    @Test void cannotAdmitBeforeSupervisorReconciliation() {
        assertThatThrownBy(() -> sut.start(binding(),UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        verify(workloads,never()).start(any(),any(),any());
    }
    @Test void activeSessionStartsAfterReconciliation() {
        sut.maintain();
        UUID attachment=UUID.randomUUID();
        sut.start(binding(),attachment);
        verify(workloads).start(eq(ID),eq(attachment),any());
    }
    @Test void revokingIsPersistedBeforeCleanupAndOnlyConfirmedCleanupBecomesRevoked() {
        doAnswer(call -> { assertThat(row.get().status()).isEqualTo(RemoteAccessSessionStatus.REVOKING); return null; }).when(workloads).stop(ID);
        assertThat(sut.revoke(binding())).isEqualTo(RemoteAccessSessionStatus.REVOKED);
        assertThat(row.get().status()).isEqualTo(RemoteAccessSessionStatus.REVOKED);
        verify(grants).remove(argThat(s -> s.status()==RemoteAccessSessionStatus.REVOKING));
    }
    @Test void failedCleanupRetainsRevokingAndDeniesNewStart() {
        sut.maintain();
        doThrow(new IllegalStateException("private detail")).when(workloads).stop(ID);
        assertThat(sut.revoke(binding())).isEqualTo(RemoteAccessSessionStatus.REVOKING);
        assertThat(row.get().status()).isEqualTo(RemoteAccessSessionStatus.REVOKING);
        assertThatThrownBy(() -> sut.start(binding(),UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        verify(workloads,never()).start(any(),any(),any());
        verify(sessions).recordFailure(any(),eq("REMOTE_ACCESS_CLEANUP_PENDING"),eq("Managed session cleanup incomplete"));
    }
    @Test void foreignKeyCannotRevokeOrStart() {
        sut.maintain();
        var foreign=new RemoteAccessKeyBinding(LOCAL,ID,"SHA256:"+"B".repeat(43));
        assertThatThrownBy(() -> sut.revoke(foreign)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sut.start(foreign,UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThat(row.get().status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
        verifyNoInteractions(grants);
    }
    @Test void pendingAdmissionCompletesBeforeRevokeAndLaterAdmissionIsDenied() throws Exception {
        sut.maintain();
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); return null; })
            .when(workloads).start(any(),any(),any());
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=executor.submit(() -> sut.start(binding(),UUID.randomUUID()));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var revoke=executor.submit(() -> sut.revoke(binding()));
            assertThat(row.get().status()).isEqualTo(RemoteAccessSessionStatus.ACTIVE);
            release.countDown();
            start.get(5,TimeUnit.SECONDS);
            assertThat(revoke.get(5,TimeUnit.SECONDS)).isEqualTo(RemoteAccessSessionStatus.REVOKED);
        } finally { release.countDown(); }
        assertThatThrownBy(() -> sut.start(binding(),UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        verify(workloads,times(1)).start(any(),any(),any());
    }
    @Test void restartRetriesRevokingCleanupBeforeAdmitting() {
        row.set(row.get().requestRevoke(NOW));
        sut.maintain();
        assertThat(row.get().status()).isEqualTo(RemoteAccessSessionStatus.REVOKED);
        verify(workloads).stop(ID);
    }
    @Test void periodicHeartbeatDoesNotWaitForRevokingProcessCleanup() {
        sut.maintain();
        row.set(row.get().requestRevoke(NOW));
        clearInvocations(workloads);
        sut.maintain();
        verify(workloads).heartbeat(any());
        verify(workloads,never()).stop(any());
    }
    @Test void unavailableAuthorityStopsRenewingSupervisorLease() {
        sut.maintain();
        clearInvocations(workloads);
        when(sessions.findLocal(LOCAL)).thenThrow(new IllegalStateException("DB unavailable"));
        assertThatThrownBy(sut::maintain).isInstanceOf(IllegalStateException.class);
        verify(workloads,never()).heartbeat(any());
        assertThatThrownBy(() -> sut.start(binding(),UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }
    @Test void successfulCleanupRetryClearsPersistedFailure() {
        doThrow(new IllegalStateException("unavailable")).doNothing().when(workloads).stop(ID);
        assertThat(sut.revoke(binding())).isEqualTo(RemoteAccessSessionStatus.REVOKING);
        assertThat(row.get().failureCode()).isEqualTo("REMOTE_ACCESS_CLEANUP_PENDING");
        assertThat(sut.revoke(binding())).isEqualTo(RemoteAccessSessionStatus.REVOKED);
        assertThat(row.get().failureCode()).isNull();
        assertThat(row.get().failureMessage()).isNull();
    }
    @Test void successfulCleanupCannotClearFailureWrittenAfterItsTransition() {
        row.set(row.get().requestRevoke(NOW).withFailure("REMOTE_ACCESS_CLEANUP_PENDING","previous failure"));
        when(sessions.transition(any(),any())).thenAnswer(call -> {
            RemoteAccessSession after=call.getArgument(1);
            row.set(after.withFailure("NEWER_FAILURE","newer writer"));
            return true;
        });
        assertThat(sut.revoke(binding())).isEqualTo(RemoteAccessSessionStatus.REVOKED);
        assertThat(row.get().failureCode()).isEqualTo("NEWER_FAILURE");
        assertThat(row.get().failureMessage()).isEqualTo("newer writer");
    }
    @Test void lostCleanupTransitionDoesNotClearConcurrentFailure() {
        row.set(row.get().requestRevoke(NOW).withFailure("REMOTE_ACCESS_CLEANUP_PENDING","previous failure"));
        when(sessions.transition(any(),any())).thenAnswer(call -> {
            row.set(row.get().withFailure("NEWER_FAILURE","newer writer"));
            return false;
        });
        assertThat(sut.revoke(binding())).isEqualTo(RemoteAccessSessionStatus.REVOKING);
        assertThat(row.get().failureCode()).isEqualTo("NEWER_FAILURE");
        verify(sessions,never()).recordFailure(any(),isNull(),isNull());
    }
}
