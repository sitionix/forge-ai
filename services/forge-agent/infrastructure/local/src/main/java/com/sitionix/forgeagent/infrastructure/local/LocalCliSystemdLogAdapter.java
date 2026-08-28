package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LocalCliSystemdLogAdapter implements SystemdLogPort {
  private final TypedProcessExecutor executor;

  @Override
  public void validate(SystemdLogConfiguration configuration, SshConnection connection) {
    executor.output(validation(configuration, connection), null, connection);
  }

  @Override
  public LogStream stream(
      SystemdLogConfiguration configuration, int initialLines, SshConnection connection) {
    int safe = Math.max(1, Math.min(initialLines, 10000));
    var command = new ArrayList<>(List.of("journalctl"));
    if (configuration.mode() == SystemdTargetMode.UNIT) {
      command.add("--unit");
      command.add(RuntimeTargetValidator.unit(configuration.unit()));
    }
    command.addAll(List.of("--lines", String.valueOf(safe), "--follow", "--output", "short-iso"));
    return executor.stream(shell(connection, command), null, connection);
  }

  private List<String> validation(SystemdLogConfiguration configuration, SshConnection connection) {
    if (configuration.mode() == SystemdTargetMode.FULL_JOURNAL)
      return shell(connection, List.of("journalctl", "--no-pager", "--lines", "0"));
    String unit = RuntimeTargetValidator.unit(configuration.unit());
    return shell(connection, List.of("systemctl", "status", "--", unit));
  }

  private List<String> shell(SshConnection connection, List<String> command) {
    return connection == null ? command : RemoteShellCommand.ssh(connection, command);
  }
}
