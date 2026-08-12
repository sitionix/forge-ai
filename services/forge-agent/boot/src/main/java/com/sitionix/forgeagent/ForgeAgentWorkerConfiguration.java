package com.sitionix.forgeagent;

import com.sitionix.forgeagent.application.runtime.AgentExecutor;
import com.sitionix.forgeagent.application.runtime.NodeRunLifecycle;
import com.sitionix.forgeagent.application.runtime.NodeRunWorker;
import com.sitionix.forgeagent.domain.port.NodeRunRepository;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    @Bean
    @ConditionalOnBean(AgentExecutor.class)
    NodeRunWorker nodeRunWorker(final NodeRunRepository nodeRunRepository,
                                final NodeRunLifecycle lifecycle,
                                final AgentExecutor agentExecutor,
                                final ExecutorService nodeRunExecutorService) {
        return new NodeRunWorker(nodeRunRepository, lifecycle, agentExecutor, nodeRunExecutorService);
    }

    @Bean
    @ConditionalOnBean(NodeRunWorker.class)
    @ConditionalOnProperty(prefix = "forge.agent.worker", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
    NodeRunPollingScheduler nodeRunPollingScheduler(final NodeRunWorker worker) {
        return new NodeRunPollingScheduler(worker);
    }

    static final class NodeRunPollingScheduler {

        private final NodeRunWorker worker;

        private NodeRunPollingScheduler(final NodeRunWorker worker) {
            this.worker = worker;
        }

        @Scheduled(
                initialDelayString = "${forge.agent.worker.poll-delay:10s}",
                fixedDelayString = "${forge.agent.worker.poll-delay:10s}"
        )
        void poll() {
            this.worker.poll();
        }
    }
}
