package com.sitionix.forgeagent.application.remoteaccess;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.port.RemoteAccessSwitchRepository;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RemoteAccessSwitchTest {
    @Test void disabledAndDisablingNeverAdmitWork() {
        var stored=new AtomicReference<>(new RemoteAccessSwitchState(RemoteAccessSwitchStatus.DISABLED,0));
        var sut=switchWith(stored);
        assertThatThrownBy(() -> sut.admit(() -> "work")).isInstanceOf(ConflictException.class);
        assertThat(sut.enable().status()).isEqualTo(RemoteAccessSwitchStatus.ENABLED);
        assertThat(sut.admit(() -> "work")).isEqualTo("work");
        assertThat(sut.beginDisable().status()).isEqualTo(RemoteAccessSwitchStatus.DISABLING);
        assertThatThrownBy(() -> sut.admit(() -> "work")).isInstanceOf(ConflictException.class);
        assertThat(sut.finishDisable().status()).isEqualTo(RemoteAccessSwitchStatus.DISABLED);
    }

    @Test void disableWaitsForAdmittedOperationBeforeClosingGate() throws Exception {
        var stored=new AtomicReference<>(new RemoteAccessSwitchState(RemoteAccessSwitchStatus.ENABLED,0));
        var sut=switchWith(stored);
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> operation=workers.submit(() -> sut.admit(() -> {
                entered.countDown();
                try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return "done";
            }));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            Future<RemoteAccessSwitchState> disable=workers.submit(sut::beginDisable);
            assertThat(disable.isDone()).isFalse();
            release.countDown();
            assertThat(operation.get(5,TimeUnit.SECONDS)).isEqualTo("done");
            assertThat(disable.get(5,TimeUnit.SECONDS).status()).isEqualTo(RemoteAccessSwitchStatus.DISABLING);
            assertThatThrownBy(() -> sut.admit(() -> "late")).isInstanceOf(ConflictException.class);
        }
    }

    private static RemoteAccessSwitch switchWith(AtomicReference<RemoteAccessSwitchState> stored) {
        var repository=mock(RemoteAccessSwitchRepository.class);
        when(repository.get()).thenAnswer(call -> stored.get());
        when(repository.transition(any(),any())).thenAnswer(call ->
                stored.compareAndSet(call.getArgument(0),call.getArgument(1)));
        return new RemoteAccessSwitch(repository);
    }
}
