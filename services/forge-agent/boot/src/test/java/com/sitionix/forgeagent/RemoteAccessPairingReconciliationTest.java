package com.sitionix.forgeagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessAccessorPairing;
import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessGrantorPairing;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

class RemoteAccessPairingReconciliationTest {
    @Test
    void slowPeerRecoveryDoesNotOccupyTheExistingApplicationScheduler() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var unrelatedTick = new CountDownLatch(1);
        var accessor = mock(RemoteAccessAccessorPairing.class);
        var grantor = mock(RemoteAccessGrantorPairing.class);
        doAnswer(call -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(accessor).reconcile();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Scheduling.class);
            context.registerBean(RemoteAccessAccessorPairing.class, () -> accessor);
            context.registerBean(RemoteAccessGrantorPairing.class, () -> grantor);
            context.registerBean(Ticker.class, () -> new Ticker(entered, unrelatedTick));
            context.registerBean(com.sitionix.forgeagent.application.remoteaccess.RemoteAccessAccessorExecution.class,
                    () -> mock(com.sitionix.forgeagent.application.remoteaccess.RemoteAccessAccessorExecution.class));
            context.register(RemoteAccessPairingReconciliation.class);
            context.refresh();
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(unrelatedTick.await(1, TimeUnit.SECONDS))
                        .as("existing scheduled work continues while SSH recovery is blocked").isTrue();
                verifyNoInteractions(grantor); // Inbound channel remains disabled by default.
            } finally {
                release.countDown();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {
        @Bean(destroyMethod = "shutdownNow")
        ScheduledExecutorService taskScheduler() {
            return Executors.newSingleThreadScheduledExecutor();
        }
    }

    static class Ticker {
        private final CountDownLatch entered;
        private final CountDownLatch tick;
        Ticker(CountDownLatch entered, CountDownLatch tick) {
            this.entered = entered;
            this.tick = tick;
        }
        @Scheduled(fixedDelay = 10)
        void tick() {
            if (entered.getCount() == 0) tick.countDown();
        }
    }
}
