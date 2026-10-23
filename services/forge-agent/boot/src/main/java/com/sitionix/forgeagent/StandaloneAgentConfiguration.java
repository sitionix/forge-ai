package com.sitionix.forgeagent;
import com.sitionix.forgeagent.application.definition.AgentDefinitionResolver;
import com.sitionix.forgeagent.application.runtime.*;
import com.sitionix.forgeagent.application.mcp.McpExecutionSelectionService;
import com.sitionix.forgeagent.application.standalone.StandaloneTurnWorker;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.standalone.StandaloneSessionRepository;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
@Configuration(proxyBeanMethods=false)
public class StandaloneAgentConfiguration {
 @Bean(destroyMethod="close") @ConditionalOnBean(AgentTurnExecutionPort.class)
 StandaloneTurnWorker standaloneTurnWorker(StandaloneSessionRepository sessions,ProjectRepository projects,ExecutionWorkspaceResolver workspaces,
        AgentDefinitionResolver definitions,AgentTurnExecutionPort execution,McpExecutionSelectionService mcp,
        ScheduledExecutorService agentSessionHeartbeatExecutor,@Value("${forge.agent.standalone.concurrency:2}")int concurrency,
        @Value("${forge.agent.standalone.timeout-seconds:600}")int timeout,com.sitionix.forgeagent.application.standalone.StandaloneSessionRecovery recovery) {
  return new StandaloneTurnWorker(sessions,projects,workspaces,definitions,execution,mcp,agentSessionHeartbeatExecutor,concurrency,timeout,recovery);
 }
 @Bean @ConditionalOnBean(StandaloneTurnWorker.class)
 @ConditionalOnProperty(prefix="forge.agent.worker",name="scheduling-enabled",havingValue="true",matchIfMissing=true)
 StandalonePolling standalonePolling(StandaloneTurnWorker worker){return new StandalonePolling(worker);}
 static final class StandalonePolling {
  private final StandaloneTurnWorker worker;StandalonePolling(StandaloneTurnWorker worker){this.worker=worker;}
  @Scheduled(fixedDelayString="${forge.agent.standalone.poll-delay:1000}")void poll(){worker.poll();}
 }
}
