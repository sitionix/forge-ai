package com.sitionix.forgeagent.infrastructure.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeagent.domain.model.SshAuthType;
import com.sitionix.forgeagent.domain.model.SshConnection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CliServiceMetricsAdapterTest {
  @Test
  void parsesRunningWholeServiceMetricsAndPreservesUnavailableValues() {
    var executor = new StubExecutor(List.of(
        "Id=alpha.service", "Description=Alpha worker", "CPUUsageNSec=2500000000",
        "MemoryCurrent=1073741824", "TasksCurrent=12", "",
        "Id=beta.service", "Description=Beta", "CPUUsageNSec=infinity",
        "MemoryCurrent=[not set]", "TasksCurrent=3"));

    var connection = connection();
    var snapshot = new CliServiceMetricsAdapter(executor,
        Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC)).collect(connection);

    assertThat(snapshot.sampledAt()).isEqualTo(Instant.parse("2026-09-02T10:00:00Z"));
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
