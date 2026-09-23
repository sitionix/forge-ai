package com.sitionix.forgeagent.application.remoteaccess;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RemoteAccessAccessorExecutionTest {
    static final UUID LOCAL=UUID.randomUUID(), ID=UUID.randomUUID(), KEY=UUID.randomUUID();
    static final Instant NOW=Instant.parse("2026-09-23T12:00:00Z");
    @Mock RemoteAccessSessionRepository sessions;
    @Mock ForgeInstanceIdentityRepository identity;
    @Mock RemoteAccessRevokeTransport transport;
    @Mock RemoteAccessCredentialStore credentials;
    @Mock RemoteAccessCommandTransport commands;
    AtomicReference<RemoteAccessSession> row;
    RemoteAccessAccessorExecution sut;
    @BeforeEach void setup() {
        row=new AtomicReference<>(new RemoteAccessSession(ID,UUID.randomUUID(),RemoteAccessRole.ACCESSOR,
            UUID.randomUUID(),LOCAL,"grantor",new RemoteAccessEndpoint("127.0.0.1",2222,"forge-ssh"),"host","key","fp",KEY,
            RemoteAccessSessionStatus.ACTIVE,NOW.minusSeconds(60),NOW.plusSeconds(240),NOW.minusSeconds(30),null,null,
            RemoteAccessConnectivity.UNKNOWN,null,null,null,null,1));
        when(identity.getOrCreate()).thenReturn(LOCAL);
        when(sessions.findById(ID)).thenAnswer(call -> Optional.of(row.get()));
        lenient().when(sessions.transition(any(),any())).thenAnswer(call -> row.compareAndSet(call.getArgument(0),call.getArgument(1)));
        lenient().when(sessions.recordFailure(any(),nullable(String.class),nullable(String.class))).thenAnswer(call -> {
            RemoteAccessSession before=call.getArgument(0);
            return row.compareAndSet(before,before.withFailure(call.getArgument(1),call.getArgument(2)));
        });
        sut=new RemoteAccessAccessorExecution(sessions,identity,transport,credentials,commands,Clock.fixed(NOW,ZoneOffset.UTC));
    }
    @Test void localRevokeIntentDeniesNewExecutionBeforeCallingTransport() {
        row.set(row.get().requestRevoke(NOW));
        assertThatThrownBy(() -> sut.start(ID,new RemoteAccessCommand(List.of("/bin/true"),"/workspace",1)))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(commands);
    }
    @Test void offlineRevokeRetainsIntentAndCredential() {
        when(transport.revoke(any())).thenThrow(new IllegalStateException("connection denied"));
        assertThat(sut.revoke(ID).status()).isEqualTo(RemoteAccessSessionStatus.REVOKING);
        assertThat(row.get().localPrivateKeyReference()).isEqualTo(KEY);
        verifyNoInteractions(credentials);
    }
    @Test void remoteConfirmationIsPersistedBeforeKeyRemovalAndReferenceClearedAfterwards() {
        when(transport.revoke(any())).thenAnswer(call -> {
            assertThat(row.get().status()).isEqualTo(RemoteAccessSessionStatus.REVOKING);
            return RemoteAccessSessionStatus.REVOKED;
        });
        doAnswer(call -> {
            assertThat(row.get().status()).isEqualTo(RemoteAccessSessionStatus.REVOKED);
            assertThat(row.get().localPrivateKeyReference()).isEqualTo(KEY);
            return null;
        }).when(credentials).delete(KEY);
        assertThat(sut.revoke(ID).status()).isEqualTo(RemoteAccessSessionStatus.REVOKED);
        assertThat(row.get().localPrivateKeyReference()).isNull();
    }
    @Test void credentialFailureRetainsReferenceAndRestartRetriesWithoutRemoteGrant() {
        when(transport.revoke(any())).thenReturn(RemoteAccessSessionStatus.REVOKED);
        doThrow(new IllegalStateException("filesystem unavailable")).doNothing().when(credentials).delete(KEY);
        var result=sut.revoke(ID);
        assertThat(result.status()).isEqualTo(RemoteAccessSessionStatus.REVOKED);
        assertThat(result.localPrivateKeyReference()).isEqualTo(KEY);
        assertThat(result.failureCode()).isEqualTo("REMOTE_ACCESS_CREDENTIAL_CLEANUP_PENDING");
        var restarted=new RemoteAccessAccessorExecution(sessions,identity,transport,credentials,commands,Clock.fixed(NOW,ZoneOffset.UTC));
        assertThat(restarted.revoke(ID).localPrivateKeyReference()).isNull();
        assertThat(row.get().failureCode()).isNull();
        assertThat(row.get().failureMessage()).isNull();
        verify(transport,times(1)).revoke(any());
        verify(credentials,times(2)).delete(KEY);
    }
    @Test void authenticatedRevokeRetryClearsFailureBeforeDeletingCredential() {
        when(transport.revoke(any())).thenThrow(new IllegalStateException("offline"))
            .thenReturn(RemoteAccessSessionStatus.REVOKED);
        assertThat(sut.revoke(ID).failureCode()).isEqualTo("REMOTE_ACCESS_REVOKE_UNCONFIRMED");
        doAnswer(call -> {
            assertThat(row.get().status()).isEqualTo(RemoteAccessSessionStatus.REVOKED);
            assertThat(row.get().failureCode()).isNull();
            assertThat(row.get().failureMessage()).isNull();
            return null;
        }).when(credentials).delete(KEY);
        var recovered=sut.revoke(ID);
        assertThat(recovered.status()).isEqualTo(RemoteAccessSessionStatus.REVOKED);
        assertThat(recovered.failureCode()).isNull();
        assertThat(recovered.failureMessage()).isNull();
    }
    @Test void remoteConfirmationCannotClearFailureWrittenAfterItsTransition() {
        row.set(row.get().requestRevoke(NOW).withFailure("REMOTE_ACCESS_REVOKE_UNCONFIRMED","previous failure"));
        when(transport.revoke(any())).thenReturn(RemoteAccessSessionStatus.REVOKED);
        when(sessions.transition(any(),any())).thenAnswer(call -> {
            RemoteAccessSession after=call.getArgument(1);
            row.set(after.withFailure("NEWER_FAILURE","newer writer"));
            return true;
        });
        var result=sut.revoke(ID);
        assertThat(result.failureCode()).isEqualTo("NEWER_FAILURE");
        assertThat(result.localPrivateKeyReference()).isEqualTo(KEY);
        verifyNoInteractions(credentials);
    }
    @Test void credentialCleanupCannotClearConcurrentFailureWhenTransitionLoses() {
        row.set(row.get().requestRevoke(NOW).confirmRemoteRevoked(NOW)
            .withFailure("REMOTE_ACCESS_CREDENTIAL_CLEANUP_PENDING","previous failure"));
        when(sessions.transition(any(),any())).thenAnswer(call -> {
            row.set(row.get().withFailure("NEWER_FAILURE","newer writer"));
            return false;
        });
        var result=sut.revoke(ID);
        assertThat(result.failureCode()).isEqualTo("NEWER_FAILURE");
        assertThat(result.localPrivateKeyReference()).isEqualTo(KEY);
        verify(sessions,never()).recordFailure(any(),isNull(),isNull());
    }
    @Test void remoteConfirmationAtomicallyResolvesOldFailureBeforeNewCredentialFailure() {
        row.set(row.get().requestRevoke(NOW).withFailure("REMOTE_ACCESS_REVOKE_UNCONFIRMED","old failure"));
        long previousVersion=row.get().version();
        when(transport.revoke(any())).thenReturn(RemoteAccessSessionStatus.REVOKED);
        lenient().doThrow(new IllegalStateException("diagnostics unavailable")).when(sessions).recordFailure(any(),isNull(),isNull());
        doAnswer(call -> {
            assertThat(row.get().status()).isEqualTo(RemoteAccessSessionStatus.REVOKED);
            assertThat(row.get().localPrivateKeyReference()).isEqualTo(KEY);
            assertThat(row.get().version()).isEqualTo(previousVersion+1);
            assertThat(row.get().failureCode()).isNull();
            assertThat(row.get().failureMessage()).isNull();
            throw new IllegalStateException("credential storage unavailable");
        }).when(credentials).delete(KEY);
        var result=sut.revoke(ID);
        assertThat(result.status()).isEqualTo(RemoteAccessSessionStatus.REVOKED);
        assertThat(result.localPrivateKeyReference()).isEqualTo(KEY);
        assertThat(result.failureCode()).isEqualTo("REMOTE_ACCESS_CREDENTIAL_CLEANUP_PENDING");
        verify(sessions,never()).recordFailure(any(),isNull(),isNull());
    }
    @Test void restartConvergesAfterPhysicalDeleteAndLostCas() {
        retryAfterUncommittedCredentialDeletion(false);
    }
    @Test void restartConvergesAfterPhysicalDeleteAndDatabaseFailure() {
        retryAfterUncommittedCredentialDeletion(true);
    }
    private void retryAfterUncommittedCredentialDeletion(boolean databaseFailure) {
        row.set(row.get().requestRevoke(NOW).confirmRemoteRevoked(NOW)
            .withFailure("REMOTE_ACCESS_CREDENTIAL_CLEANUP_PENDING","old failure"));
        // Model the credential store's idempotent missing-file deletion.
        var files=new java.util.HashSet<>(java.util.Set.of(KEY));
        doAnswer(call -> {files.remove(KEY);return null;}).when(credentials).delete(KEY);
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        when(sessions.transition(any(),any())).thenAnswer(call -> {
            RemoteAccessSession before=call.getArgument(0),target=call.getArgument(1);
            assertThat(files).isEmpty();
            assertThat(target.localPrivateKeyReference()).isNull();
            assertThat(target.failureCode()).isNull();
            assertThat(target.failureMessage()).isNull();
            assertThat(target.version()).isEqualTo(before.version()+1);
            if (attempts.getAndIncrement()==0) {
                if (databaseFailure) throw new IllegalStateException("DB unavailable before commit");
                row.set(row.get().withFailure("NEWER_FAILURE","concurrent writer"));
                return false;
            }
            return row.compareAndSet(before,target);
        });
        var pending=sut.revoke(ID);
        assertThat(files).isEmpty();
        assertThat(pending.localPrivateKeyReference()).isEqualTo(KEY);
        assertThat(pending.failureCode()).isEqualTo(databaseFailure?"REMOTE_ACCESS_CREDENTIAL_CLEANUP_PENDING":"NEWER_FAILURE");
        when(sessions.findLocal(LOCAL)).thenAnswer(call -> List.of(row.get()));
        new RemoteAccessAccessorExecution(sessions,identity,transport,credentials,commands,Clock.fixed(NOW,ZoneOffset.UTC)).reconcile();
        assertThat(row.get().status()).isEqualTo(RemoteAccessSessionStatus.REVOKED);
        assertThat(row.get().localPrivateKeyReference()).isNull();
        assertThat(row.get().failureCode()).isNull();
        assertThat(row.get().failureMessage()).isNull();
        verify(credentials,times(2)).delete(KEY);
        verify(credentials,never()).store(any(),any());
        verifyNoInteractions(transport);
        verify(sessions,never()).recordFailure(any(),isNull(),isNull());
    }
    @Test void timestampNormalizationAtUnchangedVersionDoesNotSkipCredentialDeletion() {
        row.set(row.get().requestRevoke(NOW).withFailure("REMOTE_ACCESS_REVOKE_UNCONFIRMED","previous failure"));
        when(transport.revoke(any())).thenReturn(RemoteAccessSessionStatus.REVOKED);
        when(sessions.transition(any(),any())).thenAnswer(call -> {
            RemoteAccessSession before=call.getArgument(0),target=call.getArgument(1);
            if (before.status()==RemoteAccessSessionStatus.REVOKING) {
                var normalized=before.confirmRemoteRevokedAndClearFailure(target.revokedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
                return row.compareAndSet(before,normalized);
            }
            return row.compareAndSet(before,target);
        });
        var precise=new RemoteAccessAccessorExecution(sessions,identity,transport,credentials,commands,
            Clock.fixed(NOW.plusNanos(999),ZoneOffset.UTC));
        var result=precise.revoke(ID);
        assertThat(result.status()).isEqualTo(RemoteAccessSessionStatus.REVOKED);
        assertThat(result.localPrivateKeyReference()).isNull();
        assertThat(result.failureCode()).isNull();
        verify(credentials).delete(KEY);
    }
}
