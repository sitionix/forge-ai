package com.sitionix.forgeagent;

import com.sitionix.forgeagent.api.security.McpManagementProperties;
import com.sitionix.forgeagent.domain.port.McpConnectionRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Prevents a global flag flip from returning retained MCP material to the baseline process identity. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpManagementProperties.class)
public class AgentMcpDowngradeConfiguration {
  @Bean("mcpDowngradeGuard")
  @DependsOnDatabaseInitialization
  McpDowngradeGuard mcpDowngradeGuard(McpManagementProperties settings,
      McpConnectionRepository repository) {
    final McpDowngradeGuard guard = new McpDowngradeGuard(settings, repository);
    guard.check();
    return guard;
  }
}
