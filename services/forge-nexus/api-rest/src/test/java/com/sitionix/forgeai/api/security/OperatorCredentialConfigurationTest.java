package com.sitionix.forgeai.api.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperatorCredentialConfigurationTest {
  @TempDir Path directory;

  @Test
  void enabledConfigurationRequiresDistinctProtectedBootstrapAndServiceCredentials() throws Exception {
    final Path bootstrap = credential("bootstrap", (byte) 1);
    final Path service = credential("service", (byte) 1);
    final McpManagementProperties settings = new McpManagementProperties();
    settings.setBootstrapCredentialFile(bootstrap);
    settings.setAgentServiceCredentialFile(service);

    assertThatThrownBy(() -> new OperatorManagementSecurityConfiguration().operatorBootstrap(settings))
        .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("AQE");

    settings.setAgentServiceCredentialFile(credential("different", (byte) 2));
    new OperatorManagementSecurityConfiguration().operatorBootstrap(settings);

    Files.delete(service);
    settings.setAgentServiceCredentialFile(service);
    assertThatThrownBy(() -> new OperatorManagementSecurityConfiguration().operatorBootstrap(settings))
        .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("AQE");
  }

  private Path credential(final String name, final byte value) throws Exception {
    final byte[] raw = new byte[32];
    Arrays.fill(raw, value);
    final Path file = directory.resolve(name);
    Files.writeString(file, Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
    Files.setPosixFilePermissions(file,
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    return file;
  }
}
