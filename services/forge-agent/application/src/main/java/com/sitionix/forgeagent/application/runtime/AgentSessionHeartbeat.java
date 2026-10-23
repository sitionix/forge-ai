package com.sitionix.forgeagent.application.runtime;

import com.sitionix.forgeagent.domain.model.AgentSessionExecutionClaim;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class AgentSessionHeartbeat implements AutoCloseable {
    private final ScheduledFuture<?> future;
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    public AgentSessionHeartbeat(final AgentSessionLeaseService leases, final AgentSessionExecutionClaim claim,
                          final ScheduledExecutorService scheduler, final Runnable ownershipLost) {
        this(() -> leases.renew(claim),scheduler,ownershipLost);
    }
    public AgentSessionHeartbeat(final Runnable renewal, final ScheduledExecutorService scheduler,final Runnable ownershipLost) {
        this.future = scheduler.scheduleAtFixedRate(() -> {
            try {
                renewal.run();
            } catch (RuntimeException exception) {
                if (this.failure.compareAndSet(null, exception)) ownershipLost.run();
            }
        }, AgentSessionLeaseService.HEARTBEAT_SECONDS, AgentSessionLeaseService.HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    public void verifyOwnership() {
        final RuntimeException lost = this.failure.get();
        if (lost != null) throw lost;
    }

    @Override public void close() { this.future.cancel(false); }
}
