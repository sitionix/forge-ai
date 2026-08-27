package com.sitionix.forgeagent.infrastructure.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException;
import com.sitionix.forgeagent.domain.model.RuntimeTargetCandidate;
import com.sitionix.forgeagent.domain.model.RuntimeTargetStatus;
import com.sitionix.forgeagent.domain.model.ServiceRuntimeProvider;
import com.sitionix.forgeagent.domain.model.SshAuthType;
import com.sitionix.forgeagent.domain.model.SshConnection;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CliRuntimeTargetDiscoveryAdapterTest {
  @Test
  void localDockerDiscoveryMapsRuntimeOutputToTypedCandidates() {
    var executor =
        new CapturingExecutor(List.of("abc\tforge-nexus\tUp 2 minutes\timage:1\tstack\tnexus"));

    var result = new CliRuntimeTargetDiscoveryAdapter(executor)
        .discover(null, ServiceRuntimeProvider.DOCKER);

    assertThat(executor.command)
        .containsExactly(
            "docker",
            "ps",
            "-a",
            "--format",
            "{{.ID}}\\t{{.Names}}\\t{{.Status}}\\t{{.Image}}\\t{{.Label"
                + " \"com.docker.compose.project\"}}\\t{{.Label \"com.docker.compose.service\"}}");
    assertThat(result)
        .containsExactly(
            new RuntimeTargetCandidate(
                "forge-nexus",
                "forge-nexus",
                ServiceRuntimeProvider.DOCKER,
                RuntimeTargetStatus.RUNNING,
                "image:1",
                "stack",
                "nexus"));
  }

  @Test
  void localSystemdDiscoveryMapsUnitsIncludingForgeServices() {
    var executor =
        new CapturingExecutor(
            List.of(
                "forge-agent.service loaded active running Forge Agent",
                "forge-nexus.service loaded active running Forge Nexus",
                "forge-knowledge.service loaded active running Forge Knowledge",
                "forge-jarvis.service loaded active running Forge Jarvis"));

    var result = new CliRuntimeTargetDiscoveryAdapter(executor)
        .discover(null, ServiceRuntimeProvider.SYSTEMD);

    assertThat(executor.command)
        .containsExactly(
            "systemctl",
            "list-units",
            "--type=service",
            "--all",
            "--no-legend",
            "--plain");
    assertThat(result)
        .containsExactly(
            new RuntimeTargetCandidate(
                "forge-agent.service",
                "forge-agent.service",
                ServiceRuntimeProvider.SYSTEMD,
                RuntimeTargetStatus.AVAILABLE,
                null,
                null,
                null),
            new RuntimeTargetCandidate(
                "forge-nexus.service",
                "forge-nexus.service",
                ServiceRuntimeProvider.SYSTEMD,
                RuntimeTargetStatus.AVAILABLE,
                null,
                null,
                null),
            new RuntimeTargetCandidate(
                "forge-knowledge.service",
                "forge-knowledge.service",
                ServiceRuntimeProvider.SYSTEMD,
                RuntimeTargetStatus.AVAILABLE,
                null,
                null,
                null),
            new RuntimeTargetCandidate(
                "forge-jarvis.service",
                "forge-jarvis.service",
                ServiceRuntimeProvider.SYSTEMD,
                RuntimeTargetStatus.AVAILABLE,
                null,
                null,
                null));
  }

  @Test
  void systemdUnavailableFailureIsTyped() {
    var executor =
        new CapturingExecutor(
            new InfrastructureExecutionException(
                "RUNTIME_COMMAND_FAILED", "Runtime command failed: systemd unavailable"));

    assertThatThrownBy(
            () -> new CliRuntimeTargetDiscoveryAdapter(executor)
                .discover(null, ServiceRuntimeProvider.SYSTEMD))
        .isInstanceOf(InfrastructureExecutionException.class)
        .extracting("code")
        .isEqualTo("SYSTEMD_UNAVAILABLE");
  }

  @Test
  void sshDockerDiscoveryUsesTypedSshCommand() {
    var executor = new CapturingExecutor(List.of("abc\tforge-agent\tExited\timage:1\t\t"));
    var ssh = connection();

    new CliRuntimeTargetDiscoveryAdapter(executor).discover(ssh, ServiceRuntimeProvider.DOCKER);

    assertThat(executor.command).contains("ssh", "-i", "/key", "BatchMode=yes", "--", "op@host");
    assertThat(executor.command.getLast()).contains("'docker' 'ps' '-a'");
    assertThat(executor.ssh).isSameAs(ssh);
  }

  @Test
  void sshSystemdDiscoveryUsesTypedSshCommand() {
    var executor = new CapturingExecutor(List.of("forge-nexus.service loaded active running Forge Nexus"));
    var ssh = connection();

    new CliRuntimeTargetDiscoveryAdapter(executor).discover(ssh, ServiceRuntimeProvider.SYSTEMD);

    assertThat(executor.command).contains("ssh", "-i", "/key", "BatchMode=yes", "--", "op@host");
    assertThat(executor.command.getLast()).contains("'systemctl' 'list-units'");
    assertThat(executor.ssh).isSameAs(ssh);
  }

  private SshConnection connection() {
    return new SshConnection(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "sandbox",
        "host",
        22,
        "op",
        SshAuthType.PRIVATE_KEY,
        "/key",
        null,
        Instant.EPOCH,
        Instant.EPOCH);
  }

  static final class CapturingExecutor extends TypedProcessExecutor {
    private final Object result;
    List<String> command;
    SshConnection ssh;

    CapturingExecutor(List<String> result) {
      this.result = result;
    }

    CapturingExecutor(RuntimeException result) {
      this.result = result;
    }

    @Override
    @SuppressWarnings("unchecked")
    List<String> output(List<String> command, Path cwd) {
      this.command = command;
      if (result instanceof RuntimeException failure) throw failure;
      return (List<String>) result;
    }

    @Override
    @SuppressWarnings("unchecked")
    List<String> output(List<String> command, Path cwd, SshConnection ssh) {
      this.command = command;
      this.ssh = ssh;
      if (result instanceof RuntimeException failure) throw failure;
      return (List<String>) result;
    }
  }
}
