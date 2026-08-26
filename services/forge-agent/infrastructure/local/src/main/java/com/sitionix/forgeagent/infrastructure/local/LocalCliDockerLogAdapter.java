package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.nio.file.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LocalCliDockerLogAdapter implements DockerLogPort {
  private final TypedProcessExecutor executor;

  public List<LogTargetCandidate> discover(SshConnection ssh) {
    List<String> command =
        docker(
            ssh,
            "ps",
            "-a",
            "--format",
            "{{.ID}}\\t{{.Names}}\\t{{.Status}}\\t{{.Image}}\\t{{.Label"
                + " \"com.docker.compose.project\"}}\\t{{.Label \"com.docker.compose.service\"}}");
    return output(command, null, ssh).stream()
        .filter(s -> !s.isBlank())
        .map(this::candidate)
        .toList();
  }

  public List<LogTargetCandidate> discoverComposeServices(Path repository, SshConnection ssh) {
    if (repository == null || ssh != null) return List.of();
    for (String name :
        List.of("compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml")) {
      Path file = repository.resolve(name);
      if (Files.isRegularFile(file))
        return executor
            .output(
                List.of("docker", "compose", "-f", file.toString(), "config", "--services"),
                repository)
            .stream()
            .filter(s -> !s.isBlank())
            .map(
                s ->
                    new LogTargetCandidate(
                        s, s, LogTargetStatus.AVAILABLE, null, null, s, file.toString(), false))
            .toList();
    }
    return List.of();
  }

  public void validate(String container, String service, String file, SshConnection ssh) {
    if (nonblank(service)) {
      RuntimeTargetValidator.docker(service, "Compose service");
      RuntimeTargetValidator.path(file, "Compose file");
      output(compose(ssh, file, "config", "--services"), null, ssh).stream()
          .filter(service::equals)
          .findFirst()
          .orElseThrow(() -> new ValidationException("Compose service is unavailable"));
    } else {
      RuntimeTargetValidator.docker(container, "Docker container");
      output(docker(ssh, "container", "inspect", "--", container), null, ssh);
    }
  }

  public LogStream stream(
      String container, String service, String file, int lines, SshConnection ssh) {
    int safe = Math.max(1, Math.min(lines, 10000));
    if (nonblank(service)) {
      RuntimeTargetValidator.docker(service, "Compose service");
      RuntimeTargetValidator.path(file, "Compose file");
      return stream(
          compose(
              ssh,
              file,
              "logs",
              "--tail",
              String.valueOf(safe),
              "--follow",
              "--no-color",
              "--",
              service),
          null,
          ssh);
    }
    RuntimeTargetValidator.docker(container, "Docker container");
    return stream(
        docker(ssh, "logs", "--tail", String.valueOf(safe), "--follow", "--", container),
        null,
        ssh);
  }

  private LogTargetCandidate candidate(String row) {
    String[] p = row.split("\\t", -1);
    String name = p.length > 1 && !p[1].isBlank() ? p[1] : p[0];
    return new LogTargetCandidate(
        name,
        name,
        p.length > 2 && p[2].startsWith("Up") ? LogTargetStatus.RUNNING : LogTargetStatus.STOPPED,
        p.length > 3 ? p[3] : null,
        p.length > 4 ? p[4] : null,
        p.length > 5 ? p[5] : null,
        null,
        false);
  }

  private List<String> docker(SshConnection ssh, String... args) {
    var remote = new ArrayList<String>();
    remote.add("docker");
    remote.addAll(List.of(args));
    return ssh == null ? remote : RemoteShellCommand.ssh(ssh, remote);
  }

  private List<String> compose(SshConnection ssh, String file, String... args) {
    var remote = new ArrayList<String>();
    remote.add("docker");
    remote.add("compose");
    if (nonblank(file)) {
      remote.add("-f");
      remote.add(file);
    }
    remote.addAll(List.of(args));
    return ssh == null ? remote : RemoteShellCommand.ssh(ssh, remote);
  }

  private boolean nonblank(String s) {
    return s != null && !s.isBlank();
  }

  private List<String> output(List<String> command, Path cwd, SshConnection ssh) {
    return ssh == null ? executor.output(command, cwd) : executor.output(command, cwd, ssh);
  }

  private LogStream stream(List<String> command, Path cwd, SshConnection ssh) {
    return ssh == null ? executor.stream(command, cwd) : executor.stream(command, cwd, ssh);
  }
}
