package com.sitionix.forgeagent.infrastructure.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeagent.domain.model.SshAuthType;
import com.sitionix.forgeagent.domain.model.SshConnection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliServiceMetricsAdapterTest {
  @Test
  void parsesRunningWholeServiceMetricsAndPreservesUnavailableValues() {
    var executor = new StubExecutor(List.of(
        "Id=alpha.service", "Description=Alpha worker", "CPUUsageNSec=2500000000",
        "MemoryCurrent=1073741824", "TasksCurrent=12", "",
        "Id=beta.service", "Description=Beta", "CPUUsageNSec=infinity",
        "MemoryCurrent=[not set]", "TasksCurrent=3", "",
        "FORGE_SAMPLED_AT_NANOS=1788343200123456789"));

    var connection = connection();
    var snapshot = new CliServiceMetricsAdapter(executor).collect(connection);

    assertThat(snapshot.sampledAt()).isEqualTo(Instant.parse("2026-09-02T10:00:00.123456789Z"));
    assertThat(snapshot.services()).containsExactly(
        new com.sitionix.forgeagent.domain.model.ServiceResourceMetrics(
            "alpha.service", "Alpha worker", 2500000000L, 1073741824L, 12L),
        new com.sitionix.forgeagent.domain.model.ServiceResourceMetrics(
            "beta.service", "Beta", null, null, 3L));
    assertThat(executor.command).isEqualTo(RemoteShellCommand.ssh(
        connection, List.of("sh", "-c", CliServiceMetricsAdapter.SERVICE_PROBE)));
    assertThat(executor.command).startsWith("ssh").contains("forge@server.local");
    assertThat(executor.connection).isSameAs(connection);
  }

  @Test
  void fixedProbeShowsOnlyUnitsDiscoveredAsRunning(@TempDir java.nio.file.Path temp) throws Exception {
    var capture = temp.resolve("show-args");
    var systemctl = temp.resolve("systemctl");
    java.nio.file.Files.writeString(systemctl, """
        #!/bin/sh
        if [ "$1" = "list-units" ]; then
          case " $* " in
            *' --state=running '*) printf 'alpha.service loaded active running Alpha\\n' ;;
            *) printf 'alpha.service loaded active running Alpha\\nstopped.service loaded inactive dead Stopped\\n' ;;
          esac
          exit 0
        fi
        printf '%s\\n' "$@" > "$CAPTURE"
        printf 'Id=alpha.service\\nCPUUsageNSec=1\\n'
        """);
    systemctl.toFile().setExecutable(true);
    var builder = new ProcessBuilder("sh", "-c", CliServiceMetricsAdapter.SERVICE_PROBE);
    builder.environment().put("PATH", temp + ":" + System.getenv("PATH"));
    builder.environment().put("CAPTURE", capture.toString());
    var executed = builder.start();
    assertThat(executed.waitFor()).isZero();
    assertThat(java.nio.file.Files.readString(capture)).contains("alpha.service").doesNotContain("stopped.service");
  }

  @Test
  void fixedProbeFailsWhenRunningServiceDiscoveryFails(@TempDir java.nio.file.Path temp) throws Exception {
    var systemctl = temp.resolve("systemctl");
    java.nio.file.Files.writeString(systemctl, """
        #!/bin/sh
        [ "$1" != "list-units" ] || exit 17
        exit 0
        """);
    systemctl.toFile().setExecutable(true);

    assertThat(executeProbe(temp).waitFor()).isEqualTo(17);
  }

  @Test
  void fixedProbeFailsWhenQueryingServicePropertiesFails(@TempDir java.nio.file.Path temp) throws Exception {
    var systemctl = temp.resolve("systemctl");
    java.nio.file.Files.writeString(systemctl, """
        #!/bin/sh
        if [ "$1" = "list-units" ]; then
          printf 'alpha.service loaded active running Alpha\\n'
          exit 0
        fi
        exit 23
        """);
    systemctl.toFile().setExecutable(true);

    assertThat(executeProbe(temp).waitFor()).isEqualTo(23);
  }

  @Test
  void fixedProbeSucceedsWhenNoRunningServicesAreDiscovered(@TempDir java.nio.file.Path temp) throws Exception {
    var systemctl = temp.resolve("systemctl");
    java.nio.file.Files.writeString(systemctl, """
        #!/bin/sh
        [ "$1" != "list-units" ] || exit 0
        exit 99
        """);
    systemctl.toFile().setExecutable(true);

    var executed = executeProbe(temp);
    assertThat(executed.waitFor()).isZero();
    assertThat(new String(executed.getInputStream().readAllBytes()))
        .contains("FORGE_SAMPLED_AT_NANOS=");
  }

  private static Process executeProbe(java.nio.file.Path temp) throws Exception {
    var builder = new ProcessBuilder("sh", "-c", CliServiceMetricsAdapter.SERVICE_PROBE);
    builder.environment().put("PATH", temp + ":" + System.getenv("PATH"));
    return builder.start();
  }

  private static SshConnection connection() {
    return new SshConnection(UUID.randomUUID(), UUID.randomUUID(), "server", "server.local", 22,
        "forge", SshAuthType.PRIVATE_KEY, "/key", null, Instant.EPOCH, Instant.EPOCH);
  }

  private static final class StubExecutor extends TypedProcessExecutor {
    private final List<String> rows;
    private List<String> command;
    private SshConnection connection;
    private StubExecutor(List<String> rows) { this.rows = rows; }
    @Override List<String> output(List<String> command, java.nio.file.Path cwd, SshConnection ssh) {
      this.command = command; this.connection = ssh; return rows;
    }
  }
}
