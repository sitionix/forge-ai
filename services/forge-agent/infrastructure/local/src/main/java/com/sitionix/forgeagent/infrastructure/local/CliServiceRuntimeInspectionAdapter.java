package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.ServiceRuntimeInspectionPort;
import java.time.*;
import java.time.format.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CliServiceRuntimeInspectionAdapter implements ServiceRuntimeInspectionPort {
  static final String DOCKER_FORMAT =
      "{{.State.Status}}|{{.State.StartedAt}}|{{.State.ExitCode}}|"
          + "{{if .State.Health}}{{.State.Health.Status}}{{end}}|{{.Name}}|{{.Config.Image}}";
  static final String SYSTEMD_PROPERTIES =
      "ActiveState,SubState,ExecMainStartTimestamp,MainPID,ExecMainStatus,Result";
  private static final DateTimeFormatter SYSTEMD_TIMESTAMP =
      DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm:ss z", Locale.ENGLISH);

  private final TypedProcessExecutor executor;
  private final Clock clock;

  @Override
  public ServiceRuntimeView inspect(final ProjectService service, final SshConnection ssh) {
    try {
      return service.runtimeTarget().provider() == ServiceRuntimeProvider.DOCKER
          ? inspectDocker(service.runtimeTarget(), ssh)
          : inspectSystemd(service.runtimeTarget(), ssh);
    } catch (final InfrastructureExecutionException | IllegalArgumentException exception) {
      return unknown(service.runtimeTarget(), exception.getMessage());
    }
  }

  private ServiceRuntimeView inspectDocker(
      final ServiceRuntimeTarget target, final SshConnection ssh) {
    final List<String> arguments =
        List.of(
            "docker", "inspect", "--format", DOCKER_FORMAT, "--",
            RuntimeTargetValidator.docker(target.container(), "Docker container"));
    final String line =
        output(arguments, ssh).stream()
            .findFirst()
            .orElseThrow(
                () -> new InfrastructureExecutionException("RUNTIME_EMPTY", "Docker returned no state"));
    final String[] values = line.split("\\|", -1);
    if (values.length != 6) throw new IllegalArgumentException("Docker returned invalid state");
    final int exitCode = Integer.parseInt(values[2]);
    final Instant startedAt = parseDockerTimestamp(values[1]);
    final Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("exitCode", values[2]);
    metadata.put("containerName", values[4]);
    metadata.put("image", values[5]);
    return view(
        target,
        dockerStatus(values[0], exitCode),
        startedAt,
        metadata,
        values[3].isBlank() ? null : values[3]);
  }

  private ServiceRuntimeView inspectSystemd(
      final ServiceRuntimeTarget target, final SshConnection ssh) {
    final List<String> arguments =
        List.of(
            "systemctl", "show", "--no-pager", "--property=" + SYSTEMD_PROPERTIES, "--",
            RuntimeTargetValidator.unit(target.unit()));
    final Map<String, String> values = new LinkedHashMap<>();
    for (final String line : output(arguments, ssh)) {
      final int separator = line.indexOf('=');
      if (separator > 0) values.put(line.substring(0, separator), line.substring(separator + 1));
    }
    final Instant startedAt = parseSystemdTimestamp(values.get("ExecMainStartTimestamp"));
    final Map<String, String> metadata = new LinkedHashMap<>(values);
    metadata.remove("ActiveState");
    metadata.remove("ExecMainStartTimestamp");
    return view(target, systemdStatus(values.get("ActiveState")), startedAt, metadata, null);
  }

  private List<String> output(final List<String> arguments, final SshConnection ssh) {
    return executor.output(
        ssh == null ? arguments : RemoteShellCommand.ssh(ssh, arguments), null, ssh);
  }

  private ServiceRuntimeView view(
      final ServiceRuntimeTarget target, final ServiceRuntimeStatus status,
      final Instant startedAt, final Map<String, String> metadata, final String health) {
    final Duration uptime =
        startedAt == null || startedAt.isAfter(clock.instant())
            ? null
            : Duration.between(startedAt, clock.instant());
    return new ServiceRuntimeView(
        status, target.provider(), target.connection(), target.identity(), startedAt, uptime,
        Map.copyOf(metadata), health);
  }

  private ServiceRuntimeView unknown(final ServiceRuntimeTarget target, final String message) {
    return new ServiceRuntimeView(
        ServiceRuntimeStatus.UNKNOWN, target.provider(), target.connection(), target.identity(),
        null, null,
        Map.of("inspectionError", message == null ? "Runtime inspection failed" : message), null);
  }

  private ServiceRuntimeStatus dockerStatus(final String state, final int exitCode) {
    return switch (state) {
      case "running" -> ServiceRuntimeStatus.RUNNING;
      case "exited" -> exitCode == 0 ? ServiceRuntimeStatus.STOPPED : ServiceRuntimeStatus.FAILED;
      case "dead", "removing", "restarting" -> ServiceRuntimeStatus.FAILED;
      default -> ServiceRuntimeStatus.UNKNOWN;
    };
  }

  private ServiceRuntimeStatus systemdStatus(final String state) {
    return switch (state == null ? "" : state) {
      case "active", "activating", "reloading" -> ServiceRuntimeStatus.RUNNING;
      case "inactive", "deactivating" -> ServiceRuntimeStatus.STOPPED;
      case "failed" -> ServiceRuntimeStatus.FAILED;
      default -> ServiceRuntimeStatus.UNKNOWN;
    };
  }

  private Instant parseDockerTimestamp(final String value) {
    if (value == null || value.isBlank() || value.startsWith("0001-")) return null;
    return Instant.parse(value);
  }

  private Instant parseSystemdTimestamp(final String value) {
    if (value == null || value.isBlank() || value.equalsIgnoreCase("n/a")) return null;
    try {
      return ZonedDateTime.parse(value, SYSTEMD_TIMESTAMP).toInstant();
    } catch (final DateTimeParseException ignored) {
      return null;
    }
  }
}
