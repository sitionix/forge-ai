package com.sitionix.forgeagent.infrastructure.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException;
import com.sitionix.forgeagent.domain.model.*;
import java.time.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class CliServiceRuntimeInspectionAdapterTest {
  private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");
  private final CapturingExecutor executor = new CapturingExecutor();
  private final CliServiceRuntimeInspectionAdapter adapter =
      new CliServiceRuntimeInspectionAdapter(executor, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void localDockerUsesExactTypedCommandAndMapsRunning() {
    executor.respond("running|2026-08-27T09:00:00Z|0|healthy|/api|image:1");

    final ServiceRuntimeView runtime = adapter.inspect(service(docker(ServiceConnectionType.LOCAL)), null);

    assertThat(runtime.status()).isEqualTo(ServiceRuntimeStatus.RUNNING);
    assertThat(runtime.startedAt()).isEqualTo(Instant.parse("2026-08-27T09:00:00Z"));
    assertThat(runtime.uptime()).isEqualTo(Duration.ofHours(1));
    assertThat(runtime.health()).isEqualTo("healthy");
    assertThat(executor.commands).containsExactly(
        List.of(
                "docker", "inspect", "--format",
                CliServiceRuntimeInspectionAdapter.DOCKER_FORMAT, "--", "api"));
  }

  @Test
  void dockerExitedMapsByExitCode() {
    executor.respond("exited|2026-08-27T09:00:00Z|0||/api|image:1");
    executor.respond("exited|2026-08-27T09:00:00Z|7||/api|image:1");

    assertThat(adapter.inspect(service(docker(ServiceConnectionType.LOCAL)), null).status())
        .isEqualTo(ServiceRuntimeStatus.STOPPED);
    assertThat(adapter.inspect(service(docker(ServiceConnectionType.LOCAL)), null).status())
        .isEqualTo(ServiceRuntimeStatus.FAILED);
  }

  @Test
  void malformedOrTransportFailureIsUnknown() {
    executor.respond("invalid");
    executor.fail(new InfrastructureExecutionException("RUNTIME_UNAVAILABLE", "unavailable"));

    assertThat(adapter.inspect(service(docker(ServiceConnectionType.LOCAL)), null).status())
        .isEqualTo(ServiceRuntimeStatus.UNKNOWN);
    assertThat(adapter.inspect(service(docker(ServiceConnectionType.LOCAL)), null).status())
        .isEqualTo(ServiceRuntimeStatus.UNKNOWN);
  }

  @Test
  void localSystemdUsesExactTypedCommandAndExposesStartAndUptime() {
    executor.respond(
                "ActiveState=active",
                "SubState=running",
                "ExecMainStartTimestamp=Thu 2026-08-27 09:30:00 UTC",
                "MainPID=42",
                "ExecMainStatus=0",
                "Result=success");

    final ServiceRuntimeView runtime =
        adapter.inspect(service(systemd(ServiceConnectionType.LOCAL)), null);

    assertThat(runtime.status()).isEqualTo(ServiceRuntimeStatus.RUNNING);
    assertThat(runtime.startedAt()).isEqualTo(Instant.parse("2026-08-27T09:30:00Z"));
    assertThat(runtime.uptime()).isEqualTo(Duration.ofMinutes(30));
    assertThat(executor.commands).containsExactly(
        List.of(
                "systemctl", "show", "--no-pager",
                "--property=" + CliServiceRuntimeInspectionAdapter.SYSTEMD_PROPERTIES,
                "--", "api.service"));
  }

  @Test
  void sshWrapsOnlyTheSameTypedDockerArguments() {
    final SshConnection ssh = ssh();
    executor.respond("running|2026-08-27T09:00:00Z|0||/api|image:1");

    adapter.inspect(service(docker(ServiceConnectionType.SSH)), ssh);

    assertThat(executor.commands.getFirst()).isEqualTo(
        RemoteShellCommand.ssh(
            ssh,
            List.of(
                "docker", "inspect", "--format",
                CliServiceRuntimeInspectionAdapter.DOCKER_FORMAT, "--", "api")));
    assertThat(executor.commands.getFirst().getLast()).doesNotContain(";").doesNotContain("$(");
    assertThat(executor.sshConnections.getFirst()).isSameAs(ssh);
  }

  @Test
  void systemdStateMappingsRemainNormalized() {
    executor.respond("ActiveState=inactive");
    executor.respond("ActiveState=failed");
    executor.respond("ActiveState=unknown");
    final ProjectService service = service(systemd(ServiceConnectionType.LOCAL));

    assertThat(adapter.inspect(service, null).status()).isEqualTo(ServiceRuntimeStatus.STOPPED);
    assertThat(adapter.inspect(service, null).status()).isEqualTo(ServiceRuntimeStatus.FAILED);
    assertThat(adapter.inspect(service, null).status()).isEqualTo(ServiceRuntimeStatus.UNKNOWN);
  }

  private ProjectService service(final ServiceRuntimeTarget target) {
    return new ProjectService(
        UUID.randomUUID(), UUID.randomUUID(), "api", null, target, Instant.EPOCH, Instant.EPOCH);
  }

  private ServiceRuntimeTarget docker(final ServiceConnectionType connection) {
    return new ServiceRuntimeTarget(
        connection, connection == ServiceConnectionType.SSH ? UUID.randomUUID() : null,
        ServiceRuntimeProvider.DOCKER, "api", null);
  }

  private ServiceRuntimeTarget systemd(final ServiceConnectionType connection) {
    return new ServiceRuntimeTarget(
        connection, connection == ServiceConnectionType.SSH ? UUID.randomUUID() : null,
        ServiceRuntimeProvider.SYSTEMD, null, "api.service");
  }

  private SshConnection ssh() {
    return new SshConnection(
        UUID.randomUUID(), UUID.randomUUID(), "host", "host.local", 22, "operator",
        "/keys/id", Instant.EPOCH, Instant.EPOCH);
  }

  static final class CapturingExecutor extends TypedProcessExecutor {
    private final Deque<Object> results = new ArrayDeque<>();
    final List<List<String>> commands = new ArrayList<>();
    final List<SshConnection> sshConnections = new ArrayList<>();

    void respond(String... lines) {
      results.addLast(List.of(lines));
    }

    void fail(RuntimeException failure) {
      results.addLast(failure);
    }

    @Override
    @SuppressWarnings("unchecked")
    List<String> output(List<String> command, Path cwd, SshConnection ssh) {
      commands.add(List.copyOf(command));
      sshConnections.add(ssh);
      Object result = results.removeFirst();
      if (result instanceof RuntimeException failure) throw failure;
      return (List<String>) result;
    }
  }
}
