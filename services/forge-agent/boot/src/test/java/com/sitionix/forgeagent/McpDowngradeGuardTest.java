package com.sitionix.forgeagent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sitionix.forgeagent.api.security.McpManagementProperties;
import com.sitionix.forgeagent.domain.port.McpConnectionRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class McpDowngradeGuardTest {
  @Test
  void freshOffPermitsStartupButRetainedRowsAndQueryFailureRefuse() {
    final var settings = new McpManagementProperties();
    final var repository = mock(McpConnectionRepository.class);
    final var guard = new McpDowngradeGuard(settings, repository);
    when(repository.hasRetainedCredentials()).thenReturn(false, true)
        .thenThrow(new IllegalStateException("synthetic database failure"));
    guard.check();
    assertThatThrownBy(guard::check).isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("synthetic database failure");
    assertThatThrownBy(guard::check).isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("synthetic database failure");
    verify(repository, times(3)).hasRetainedCredentials();
  }

  @Test
  void configuredPathsRefuseWithoutReadingFilesOrDatabaseAndEnabledModePermits() {
    final var settings = new McpManagementProperties();
    final var repository = mock(McpConnectionRepository.class);
    settings.setKeyFile(Path.of("/synthetic/key"));
    assertThatThrownBy(() -> new McpDowngradeGuard(settings, repository).check())
        .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("/synthetic/key");
    verifyNoInteractions(repository);
    settings.setEnabled(true);
    new McpDowngradeGuard(settings, repository).check();
    verifyNoInteractions(repository);
  }

  @Test
  void retainedRowsFailContextBeforeEarlyBackgroundReconciliationIsConstructed() {
    final var repository = mock(McpConnectionRepository.class);
    when(repository.hasRetainedCredentials()).thenReturn(true);
    new ApplicationContextRunner()
        .withUserConfiguration(RemoteAccessPairingReconciliation.class,
            AgentMcpDowngradeConfiguration.class)
        .withBean(McpConnectionRepository.class, () -> repository)
        .run(context -> {
          assertThat(context.getStartupFailure()).isNotNull()
              .hasRootCauseMessage("MCP downgrade refused: protected material retained or unavailable");
          verify(repository).hasRetainedCredentials();
        });
  }
}
