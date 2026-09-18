package com.sitionix.forgeagent;

import com.sitionix.forgeagent.application.runtime.AgentExecutor;
import com.sitionix.forgeagent.application.runtime.AgentExecutionRecoveryService;
import com.sitionix.forgeagent.application.runtime.AgentSessionLeaseService;
import com.sitionix.forgeagent.application.runtime.NodeRunCompletionProcessor;
import com.sitionix.forgeagent.application.runtime.NodeRunCompletionWorker;
import com.sitionix.forgeagent.application.runtime.NodeRunLifecycle;
import com.sitionix.forgeagent.application.runtime.NodeRunWorker;
import com.sitionix.forgeagent.application.runtime.WorkflowExecutionCoordinator;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ForgeAgentWorkerConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(AgentExecutor.class)
    ExecutorService nodeRunExecutorService() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService agentSessionHeartbeatExecutor() {
        return Executors.newScheduledThreadPool(2);
    }

    @Bean
    @ConditionalOnBean(AgentExecutor.class)
    NodeRunWorker nodeRunWorker(final NodeRunRepository nodeRunRepository,
                                final NodeRunLifecycle lifecycle,
                                final AgentExecutor agentExecutor,
                                final ExecutorService nodeRunExecutorService,
                                final ScheduledExecutorService agentSessionHeartbeatExecutor,
                                final AgentSessionLeaseService sessionLeaseService,
                                final AgentExecutionRecoveryService recoveryService,
                                final com.sitionix.forgeagent.application.runtime.ManualNodeRunLifecycle manualLifecycle) {
        return new NodeRunWorker(nodeRunRepository, lifecycle, agentExecutor, nodeRunExecutorService,
                agentSessionHeartbeatExecutor, sessionLeaseService, recoveryService, manualLifecycle);
    }

    @Bean
    @ConditionalOnBean(AgentExecutor.class)
    NodeRunCompletionWorker nodeRunCompletionWorker(final NodeRunRepository nodeRunRepository,
                                                    final NodeRunCompletionProcessor processor,
                                                    final WorkflowExecutionCoordinator coordinator) {
        return new NodeRunCompletionWorker(nodeRunRepository, processor, coordinator);
    }

    @Bean
    @ConditionalOnBean(NodeRunWorker.class)
    @ConditionalOnProperty(prefix = "forge.agent.worker", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
    NodeRunPollingScheduler nodeRunPollingScheduler(final NodeRunWorker worker) {
        return new NodeRunPollingScheduler(worker);
    }

    @Bean
    @ConditionalOnBean(NodeRunCompletionWorker.class)
    @ConditionalOnProperty(prefix = "forge.agent.worker", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
    NodeRunCompletionPollingScheduler nodeRunCompletionPollingScheduler(final NodeRunCompletionWorker worker) {
        return new NodeRunCompletionPollingScheduler(worker);
    }

    @RequiredArgsConstructor
    static final class NodeRunPollingScheduler {

        private final NodeRunWorker worker;

        @Scheduled(
                initialDelayString = "${forge.agent.worker.poll-delay:10000}",
                fixedDelayString = "${forge.agent.worker.poll-delay:10000}"
        )
        void poll() {
            this.worker.poll();
        }
    }

    @RequiredArgsConstructor
    static final class NodeRunCompletionPollingScheduler {

        private final NodeRunCompletionWorker worker;

        @Scheduled(
                initialDelayString = "${forge.agent.worker.completion-poll-delay:${forge.agent.worker.poll-delay:10000}}",
                fixedDelayString = "${forge.agent.worker.completion-poll-delay:${forge.agent.worker.poll-delay:10000}}"
        )
        void poll() {
            this.worker.poll();
        }
    }
}
