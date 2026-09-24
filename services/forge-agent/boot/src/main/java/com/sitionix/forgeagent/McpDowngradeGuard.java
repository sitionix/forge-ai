package com.sitionix.forgeagent;

import com.sitionix.forgeagent.api.security.McpManagementProperties;
import com.sitionix.forgeagent.domain.port.McpConnectionRepository;
import java.nio.file.Path;
import java.util.Objects;

/** Metadata-only, fail-closed downgrade decision; never opens credential files. */
public final class McpDowngradeGuard {
  private final McpManagementProperties settings;
  private final McpConnectionRepository repository;

  public McpDowngradeGuard(McpManagementProperties settings, McpConnectionRepository repository) {
    this.settings = Objects.requireNonNull(settings);
    this.repository = Objects.requireNonNull(repository);
  }

  public void check() {
    if (settings.isEnabled()) return;
    if (configured(settings.getKeyFile()) || configured(settings.getServiceCredentialFile())
        || configured(settings.getDatabaseCredentialFile())) {
      throw refused();
    }
    try {
      if (repository.hasRetainedCredentials()) throw refused();
    } catch (RuntimeException exception) {
      throw refused();
    }
  }

  private static boolean configured(Path value) {
    return value != null && !value.toString().isBlank();
  }

  private static IllegalStateException refused() {
    return new IllegalStateException("MCP downgrade refused: protected material retained or unavailable");
  }
}
