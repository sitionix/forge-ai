package com.sitionix.forgeagent;

import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessAccessorPairing;
import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessGrantorPairing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

@Component
@DependsOn("mcpDowngradeGuard")
@RequiredArgsConstructor
@Slf4j
public class RemoteAccessPairingReconciliation {
    private final RemoteAccessGrantorPairing grantor;
    private final RemoteAccessAccessorPairing accessor;
    private final com.sitionix.forgeagent.application.remoteaccess.RemoteAccessAccessorExecution execution;

    @Value("${forge.agent.remote-access.channel-enabled:false}")
    private boolean channelEnabled;

    // Keep potentially slow SSH recovery off the existing workflow/lease scheduler.
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "remote-access-pairing-recovery");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    public void start() {
        timer.scheduleWithFixedDelay(this::reconcile, 1, 10, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void close() {
        timer.shutdownNow();
    }

    public void reconcile() {
        try { if (channelEnabled) grantor.reconcile(); }
        catch (RuntimeException unavailable) { log.warn("Grantor pairing reconciliation unavailable; will retry"); }
        try { execution.reconcile(); }
        catch (RuntimeException unavailable) { log.warn("Accessor revoke reconciliation unavailable; intent retained"); }
        try { accessor.reconcile(); }
        catch (RuntimeException unavailable) { log.warn("Accessor pairing reconciliation unavailable; will retry"); }
    }
}
