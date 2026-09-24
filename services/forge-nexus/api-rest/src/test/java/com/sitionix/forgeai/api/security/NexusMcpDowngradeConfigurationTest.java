package com.sitionix.forgeai.api.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NexusMcpDowngradeConfigurationTest {
  @Test
  void offModeAcceptsFreshConfigurationAndRejectsEitherRetainedPathWithoutFileRead() {
    final var settings = new McpManagementProperties();
    final var configuration = new NexusMcpDowngradeConfiguration();
    configuration.nexusMcpDowngradeGuard(settings);
    settings.setBootstrapCredentialFile(Path.of("/synthetic/bootstrap"));
    assertThatThrownBy(() -> configuration.nexusMcpDowngradeGuard(settings))
        .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("/synthetic/bootstrap");
    settings.setBootstrapCredentialFile(null);
    settings.setAgentServiceCredentialFile(Path.of("/synthetic/service"));
    assertThatThrownBy(() -> configuration.nexusMcpDowngradeGuard(settings))
        .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("/synthetic/service");
  }

  @Test
  void configuredOffPathFailsStartupEvenWhenFileDoesNotExist() {
    new ApplicationContextRunner()
        .withUserConfiguration(NexusMcpDowngradeConfiguration.class)
        .withPropertyValues("forge.mcp.enabled=false",
            "forge.mcp.bootstrap-credential-file=/synthetic/retained/bootstrap")
        .run(context -> assertThat(context.getStartupFailure()).isNotNull()
            .hasRootCauseMessage("MCP downgrade refused: protected material retained"));
    new ApplicationContextRunner()
        .withUserConfiguration(NexusMcpDowngradeConfiguration.class)
        .withPropertyValues("forge.mcp.enabled=false")
        .run(context -> assertThat(context.getStartupFailure()).isNull());
  }
}
