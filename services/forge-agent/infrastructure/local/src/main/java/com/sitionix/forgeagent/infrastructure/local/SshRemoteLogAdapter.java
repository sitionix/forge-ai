package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SshRemoteLogAdapter implements RemoteLogPort, SshConnectionProbePort {
  private final TypedProcessExecutor executor;

  @Override
  public void test(SshConnection connection) {
    executor.output(command(connection, "true"), null, connection);
  }

  public List<LogTargetCandidate> discover(SshConnection c, LogProviderType provider) {
    if (provider != LogProviderType.SYSTEMD) return List.of();
    return executor
        .output(
            command(
                c, "systemctl", "list-units", "--type=service", "--all", "--no-legend", "--plain"),
            null,
            c)
        .stream()
        .map(String::strip)
        .filter(s -> !s.isBlank())
        .map(s -> s.split("\\s+", 2)[0])
        .map(
            u ->
                new LogTargetCandidate(
                    u, u, LogTargetStatus.AVAILABLE, null, null, null, null, false))
        .toList();
  }

  public void validate(SshConnection c, LogProviderType p, LogProviderConfiguration cfg) {
    executor.output(validation(c, p, cfg), null, c);
  }

  public LogStream stream(
      SshConnection c, LogProviderType p, LogProviderConfiguration cfg, int lines) {
    int safe = Math.max(1, Math.min(lines, 10000));
    return switch (p) {
      case SYSTEMD -> {
        String unit = RuntimeTargetValidator.unit(((SystemdLogConfiguration) cfg).unit());
        yield executor.stream(
            command(
                c,
                "journalctl",
                "--unit",
                unit,
                "--lines",
                String.valueOf(safe),
                "--follow",
                "--output",
                "short-iso"),
            null,
            c);
      }
      case FILE -> {
        String path = RuntimeTargetValidator.path(((FileLogConfiguration) cfg).path(), "File path");
        yield executor.stream(
            command(c, "tail", "--lines", String.valueOf(safe), "--follow=name", "--", path),
            null,
            c);
      }
      default -> throw new ValidationException("Unsupported remote provider");
    };
  }

  private List<String> validation(
      SshConnection c, LogProviderType p, LogProviderConfiguration cfg) {
    return switch (p) {
      case SYSTEMD -> {
        String u = RuntimeTargetValidator.unit(((SystemdLogConfiguration) cfg).unit());
        yield command(c, "systemctl", "status", "--", u);
      }
      case FILE -> {
        String f = RuntimeTargetValidator.path(((FileLogConfiguration) cfg).path(), "File path");
        yield command(c, "test", "-r", f);
      }
      default -> throw new ValidationException("Unsupported remote provider");
    };
  }

  private List<String> command(SshConnection s, String... remote) {
    return RemoteShellCommand.ssh(s, List.of(remote));
  }
}
