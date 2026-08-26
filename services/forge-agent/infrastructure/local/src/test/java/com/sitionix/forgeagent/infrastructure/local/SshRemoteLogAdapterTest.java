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

  @Test
  void passwordAuthenticationIsSharedByDockerSystemdAndFileOperations() {
    var executor = new CapturingExecutor();
    var password = passwordConnection("s3cr3t;$(still-data)`literal`");
    var remote = new SshRemoteLogAdapter(executor);

    remote.validate(password, LogProviderType.SYSTEMD, new SystemdLogConfiguration("rover.service"));
    assertThat(executor.command)
        .startsWith("sshpass", "-e", "ssh")
        .contains("PreferredAuthentications=password", "PubkeyAuthentication=no");
    assertThat(executor.command).doesNotContain(password.password());
    assertThat(RemoteShellCommand.environment(password)).containsEntry("SSHPASS", password.password());

    remote.validate(password, LogProviderType.FILE, new FileLogConfiguration("/var/log/app.log"));
    new LocalCliDockerLogAdapter(executor).validate("mission", null, null, password);
    assertThat(executor.ssh).isSameAs(password);

    remote.stream(password, LogProviderType.SYSTEMD, new SystemdLogConfiguration("rover.service"), 100);
    assertThat(executor.command.getLast()).contains("'journalctl'");
    new LocalCliDockerLogAdapter(executor).stream("mission", null, null, 100, password);
    assertThat(executor.command.getLast()).contains("'docker' 'logs'");
  }

  @Test
  void probeUsesTheSamePasswordTransportWithFixedTrueCommand() {
    var executor = new CapturingExecutor();
    var connection = passwordConnection("p@ss;$(safe)");

    new SshRemoteLogAdapter(executor).test(connection);

    assertThat(executor.command)
        .contains("sshpass", "-e", "PreferredAuthentications=password", "PubkeyAuthentication=no")
        .doesNotContain(connection.password());
    assertThat(executor.command.getLast()).isEqualTo("'true'");
    assertThat(RemoteShellCommand.environment(executor.ssh))
        .containsEntry("SSHPASS", connection.password());
  }

  @Test
  void probePreservesPrivateKeyAuthentication() {
    var executor = new CapturingExecutor();

    new SshRemoteLogAdapter(executor).test(connection());

    assertThat(executor.command).contains("ssh", "-i", "/key", "BatchMode=yes");
    assertThat(executor.command).doesNotContain("sshpass", "PreferredAuthentications=password");
    assertThat(RemoteShellCommand.environment(executor.ssh)).isEmpty();
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

  private SshConnection passwordConnection(String password) {
    return new SshConnection(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "ancestor",
        "192.168.0.108",
        22,
        "ancestor",
        SshAuthType.PASSWORD,
        null,
        password,
        Instant.EPOCH,
        Instant.EPOCH);
  }

  static final class CapturingExecutor extends TypedProcessExecutor {
    List<String> command;
    SshConnection ssh;

    @Override
    List<String> output(List<String> command, Path cwd, SshConnection ssh) {
      this.command = command;
      this.ssh = ssh;
      return List.of();
    }

    @Override
    ProcessLogStream stream(List<String> command, Path cwd, SshConnection ssh) {
      this.command = command;
      this.ssh = ssh;
      try {
        return new ProcessLogStream(new ProcessBuilder("true").start());
      } catch (java.io.IOException exception) {
        throw new java.io.UncheckedIOException(exception);
      }
    }
  }
}
