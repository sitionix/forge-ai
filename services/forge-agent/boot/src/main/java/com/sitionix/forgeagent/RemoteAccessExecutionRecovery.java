package com.sitionix.forgeagent;

import com.sitionix.forgeagent.application.remoteaccess.RemoteAccessExecutionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

@Component
@DependsOn("remoteAccessChannelServer")
@ConditionalOnProperty(name="forge.agent.remote-access.channel-enabled",havingValue="true")
@RequiredArgsConstructor
@Slf4j
public class RemoteAccessExecutionRecovery {
    private final RemoteAccessExecutionService execution;
    // Cleanup never occupies the liveness worker, even when one session cannot stop.
    private final ScheduledExecutorService heartbeat=timer("remote-access-authority-heartbeat");
    private final ScheduledExecutorService cleanup=timer("remote-access-workload-cleanup");
    private static ScheduledExecutorService timer(String name) {
        return Executors.newSingleThreadScheduledExecutor(task -> {var t=new Thread(task,name);t.setDaemon(true);return t;});
    }
    @PostConstruct public void start() {
        heartbeat.scheduleWithFixedDelay(() -> {
            try { execution.maintain(); } catch (RuntimeException unavailable) { log.warn("Workload authority unavailable; admissions fail closed"); }
        },1,2,TimeUnit.SECONDS);
        cleanup.scheduleWithFixedDelay(() -> {
            try { execution.reconcile(); } catch (RuntimeException unavailable) { log.warn("Workload cleanup incomplete; persisted intent retained"); }
        },3,5,TimeUnit.SECONDS);
    }
    @PreDestroy public void close() { heartbeat.shutdownNow();cleanup.shutdownNow(); }
}
