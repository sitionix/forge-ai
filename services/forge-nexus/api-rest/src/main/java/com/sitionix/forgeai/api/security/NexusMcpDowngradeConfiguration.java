package com.sitionix.forgeai.api.security;

import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Rejects a flag-only downgrade while protected Nexus credential paths remain configured. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpManagementProperties.class)
public class NexusMcpDowngradeConfiguration {
  @Bean("nexusMcpDowngradeGuard")
  Object nexusMcpDowngradeGuard(McpManagementProperties settings) {
    if (settings.isEnabled()) return new Object();
    if (configured(settings.getBootstrapCredentialFile())
        || configured(settings.getAgentServiceCredentialFile())) {
      throw new IllegalStateException("MCP downgrade refused: protected material retained");
    }
    return new Object();
  }

  private static boolean configured(Path value) {
    return value != null && !value.toString().isBlank();
  }
}
