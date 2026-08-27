package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException;
import com.sitionix.forgeagent.domain.model.RuntimeTargetCandidate;
import com.sitionix.forgeagent.domain.model.RuntimeTargetStatus;
import com.sitionix.forgeagent.domain.model.ServiceRuntimeProvider;
import com.sitionix.forgeagent.domain.model.SshConnection;
import com.sitionix.forgeagent.domain.port.RuntimeTargetDiscoveryPort;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CliRuntimeTargetDiscoveryAdapter implements RuntimeTargetDiscoveryPort {
  private final TypedProcessExecutor executor;

  @Override
  public List<RuntimeTargetCandidate> discover(
      SshConnection connection, ServiceRuntimeProvider provider) {
    return switch (provider) {
      case DOCKER -> discoverDocker(connection);
      case SYSTEMD -> discoverSystemd(connection);
    };
  }

  private List<RuntimeTargetCandidate> discoverDocker(SshConnection connection) {
    return output(
            connection,
            command(
                connection,
                "docker",
                "ps",
                "-a",
                "--format",
                "{{.ID}}\\t{{.Names}}\\t{{.Status}}\\t{{.Image}}\\t{{.Label"
                    + " \"com.docker.compose.project\"}}\\t{{.Label \"com.docker.compose.service\"}}"))
        .stream()
        .filter(row -> !row.isBlank())
        .map(this::dockerCandidate)
        .toList();
  }

  private List<RuntimeTargetCandidate> discoverSystemd(SshConnection connection) {
    try {
      return output(
              connection,
              command(
                  connection,
                  "systemctl",
                  "list-units",
                  "--type=service",
                  "--all",
                  "--no-legend",
                  "--plain"))
          .stream()
          .map(String::strip)
          .filter(row -> !row.isBlank())
          .map(row -> row.split("\\s+", 2)[0])
          .map(
              unit ->
                  new RuntimeTargetCandidate(
                      unit,
                      unit,
                      ServiceRuntimeProvider.SYSTEMD,
                      RuntimeTargetStatus.AVAILABLE,
                      null,
                      null,
                      null))
          .toList();
    } catch (InfrastructureExecutionException exception) {
      if ("RUNTIME_COMMAND_FAILED".equals(exception.code())
          || "RUNTIME_UNAVAILABLE".equals(exception.code())) {
        throw new InfrastructureExecutionException(
            "SYSTEMD_UNAVAILABLE", "Systemd is not available on the selected host.");
      }
      throw exception;
    }
  }

  private RuntimeTargetCandidate dockerCandidate(String row) {
    String[] parts = row.split("\\t", -1);
    String id = parts.length > 1 && !parts[1].isBlank() ? parts[1] : parts[0];
    return new RuntimeTargetCandidate(
        id,
        id,
        ServiceRuntimeProvider.DOCKER,
        parts.length > 2 && parts[2].startsWith("Up")
            ? RuntimeTargetStatus.RUNNING
            : RuntimeTargetStatus.STOPPED,
        parts.length > 3 ? parts[3] : null,
        parts.length > 4 ? parts[4] : null,
        parts.length > 5 ? parts[5] : null);
  }

  private List<String> command(SshConnection connection, String... command) {
    var typed = new ArrayList<>(List.of(command));
    return connection == null ? typed : RemoteShellCommand.ssh(connection, typed);
  }

  private List<String> output(SshConnection connection, List<String> command) {
    return connection == null ? executor.output(command, null) : executor.output(command, null, connection);
  }
}
