package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.AgentSessionExecutionClaim;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class AgentSessionHeartbeat implements AutoCloseable {
    private final ScheduledFuture<?> future;
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    AgentSessionHeartbeat(final AgentSessionLeaseService leases, final AgentSessionExecutionClaim claim,
                          final ScheduledExecutorService scheduler, final Runnable ownershipLost) {
        this.future = scheduler.scheduleAtFixedRate(() -> {
            try {
                leases.renew(claim);
            } catch (RuntimeException exception) {
                if (this.failure.compareAndSet(null, exception)) ownershipLost.run();
            }
        }, AgentSessionLeaseService.HEARTBEAT_SECONDS, AgentSessionLeaseService.HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    void verifyOwnership() {
        final RuntimeException lost = this.failure.get();
        if (lost != null) throw lost;
    }

    @Override public void close() { this.future.cancel(false); }
}
