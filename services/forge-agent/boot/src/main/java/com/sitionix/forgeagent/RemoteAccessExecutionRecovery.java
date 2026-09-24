package com.sitionix.forgeagent;

import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessExecutionService;
import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessControlService;
import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessMutualPairing;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

@Component
@DependsOn({"mcpDowngradeGuard", "remoteAccessChannelServer"})
@ConditionalOnProperty(name="forge.agent.remote-access.channel-enabled",havingValue="true")
@RequiredArgsConstructor
@Slf4j
public class RemoteAccessExecutionRecovery {
    private final RemoteAccessExecutionService execution;
    private final ObjectProvider<RemoteAccessControlService> control;
    private final ObjectProvider<RemoteAccessMutualPairing> mutualPairing;
    // Cleanup never occupies the liveness worker, even when one session cannot stop.
    private final ScheduledExecutorService heartbeat=timer("remote-access-authority-heartbeat");
    private final ScheduledExecutorService cleanup=timer("remote-access-workload-cleanup");
    private final ScheduledExecutorService pairing=timer("remote-access-mutual-pairing");
    private static ScheduledExecutorService timer(String name) {
        return Executors.newSingleThreadScheduledExecutor(task -> {var t=new Thread(task,name);t.setDaemon(true);return t;});
    }
    @PostConstruct public void start() {
        heartbeat.scheduleWithFixedDelay(() -> {
            try { execution.maintain(); } catch (RuntimeException unavailable) { log.warn("Workload authority unavailable; admissions fail closed"); }
        },1,2,TimeUnit.SECONDS);
        cleanup.scheduleWithFixedDelay(() -> {
            try { execution.reconcile(); control.ifAvailable(RemoteAccessControlService::reconcile); }
            catch (RuntimeException unavailable) { log.warn("Remote Access cleanup incomplete; persisted intent retained"); }
        },3,5,TimeUnit.SECONDS);
        pairing.scheduleWithFixedDelay(() -> {
            try { mutualPairing.ifAvailable(RemoteAccessMutualPairing::reconcile); }
            catch (RuntimeException unavailable) { log.warn("Remote Access pairing reconciliation incomplete"); }
        },3,5,TimeUnit.SECONDS);
    }
    @PreDestroy public void close() { heartbeat.shutdownNow();cleanup.shutdownNow();pairing.shutdownNow(); }
}
