package com.sitionix.forgeagent.application.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.AgentSessionExecutionClaim;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentSessionHeartbeatTest {

    @Test
    void leaseLossImmediatelyCancelsActiveExternalExecution() {
        final AgentSessionLeaseService leases = mock(AgentSessionLeaseService.class);
        final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        final ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        final AtomicReference<Runnable> heartbeatTask = new AtomicReference<>();
        when(scheduler.scheduleAtFixedRate(org.mockito.ArgumentMatchers.any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.SECONDS)))
                .thenAnswer(invocation -> {
                    heartbeatTask.set(invocation.getArgument(0));
                    return scheduled;
                });
        final AgentSessionExecutionClaim claim = new AgentSessionExecutionClaim(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "owner", 7,
                Instant.now().plusSeconds(30), "thread", "codex");
        doThrow(new ConflictException("STALE_AGENT_SESSION_LEASE", "lost")).when(leases).renew(claim);
        final AtomicBoolean cancelled = new AtomicBoolean();

        new AgentSessionHeartbeat(leases, claim, scheduler, () -> cancelled.set(true));
        heartbeatTask.get().run();

        assertThat(cancelled).isTrue();
    }
}
