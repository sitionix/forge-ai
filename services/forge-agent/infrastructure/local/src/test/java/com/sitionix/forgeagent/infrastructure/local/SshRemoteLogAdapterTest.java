package com.sitionix.forgeagent.infrastructure.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeagent.domain.model.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SshRemoteLogAdapterTest {
  @Test
  void systemdAndFileAreConvertedToQuotedTypedRemoteCommands() {
    var executor = new CapturingExecutor();
    var adapter = new SshRemoteLogAdapter(executor);
    var ssh = connection();
    adapter.validate(ssh, LogProviderType.SYSTEMD, new SystemdLogConfiguration("rover.service"));
    assertThat(executor.command.getLast()).isEqualTo("'systemctl' 'status' '--' 'rover.service'");
    adapter.validate(ssh, LogProviderType.FILE, new FileLogConfiguration("/var/log/a file.log"));
    assertThat(executor.command.getLast()).isEqualTo("'test' '-r' '/var/log/a file.log'");
  }

  @Test
  void dockerRemoteCommandNeverSplitsUserInputIntoLocalSshArguments() {
    var executor = new CapturingExecutor();
    new LocalCliDockerLogAdapter(executor).validate("mission", null, null, connection());
    assertThat(executor.command).contains("--", "op@rover.local");
    assertThat(executor.command.getLast())
        .isEqualTo("'docker' 'container' 'inspect' '--' 'mission'");
  }

  private SshConnection connection() {
    return new SshConnection(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "r",
        "rover.local",
        22,
        "op",
        "/key",
        Instant.EPOCH,
        Instant.EPOCH);
  }

  static final class CapturingExecutor extends TypedProcessExecutor {
    List<String> command;

    @Override
    List<String> output(List<String> command, Path cwd) {
      this.command = command;
      return List.of();
    }
  }
}
