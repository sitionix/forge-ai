package com.sitionix.forgeagent.api;

import com.sitionix.forgeagent.api.dto.*;
import com.sitionix.forgeagent.application.usecase.*;
import com.sitionix.forgeagent.domain.model.*;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
public class ProjectLogsController {
  private final LogSourceUseCases logs;
  private final SshConnectionUseCases ssh;
  private final ProjectLogSseService streaming;

  @GetMapping("/api/v1/projects/{projectId}/log-sources")
  public List<LogSourceResponse> list(@PathVariable UUID projectId) {
    return logs.list(projectId).stream().map(this::response).toList();
  }

  @PostMapping("/api/v1/projects/{projectId}/log-sources")
  public ResponseEntity<LogSourceResponse> create(
      @PathVariable UUID projectId, @Valid @RequestBody LogSourceRequest r) {
    var s = logs.create(projectId, command(r));
    return ResponseEntity.status(HttpStatus.CREATED).body(response(s));
  }

  @PutMapping("/api/v1/projects/{projectId}/log-sources/{id}")
  public LogSourceResponse update(
      @PathVariable UUID projectId, @PathVariable UUID id, @Valid @RequestBody LogSourceRequest r) {
    return response(logs.update(projectId, id, command(r)));
  }

  @DeleteMapping("/api/v1/projects/{projectId}/log-sources/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID projectId, @PathVariable UUID id) {
    logs.delete(projectId, id);
  }

  @PostMapping("/api/v1/projects/{projectId}/log-sources/discover")
  public List<LogTargetCandidate> discover(
      @PathVariable UUID projectId, @Valid @RequestBody LogDiscoveryRequest r) {
    return logs.discover(
        projectId, r.connection(), r.sshConnectionId(), r.provider(), r.repositoryId());
  }

  @PostMapping("/api/v1/projects/{projectId}/log-sources/validate")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void validate(@PathVariable UUID projectId, @Valid @RequestBody LogSourceRequest r) {
    logs.validateTarget(projectId, command(r));
  }

  @GetMapping(
      path = "/api/v1/projects/{projectId}/logs/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @PathVariable UUID projectId,
      @RequestParam List<UUID> sourceId,
      @RequestParam(defaultValue = "100") int lines) {
    return streaming.stream(projectId, sourceId, lines);
  }

  @GetMapping("/api/v1/projects/{projectId}/ssh-connections")
  public List<SshConnectionResponse> sshList(@PathVariable UUID projectId) {
    return ssh.list(projectId).stream().map(this::sshResponse).toList();
  }

  @PostMapping("/api/v1/projects/{projectId}/ssh-connections")
  @ResponseStatus(HttpStatus.CREATED)
  public SshConnectionResponse sshCreate(
      @PathVariable UUID projectId, @Valid @RequestBody SshConnectionRequest r) {
    return sshResponse(
        ssh.create(
            projectId,
            new SaveSshConnectionCommand(
                r.name(), r.host(), r.port(), r.username(), r.privateKeyPath())));
  }

  private SaveLogSourceCommand command(LogSourceRequest r) {
    LogProviderConfiguration c =
        switch (r.provider()) {
          case DOCKER ->
              new DockerLogConfiguration(r.container(), r.composeService(), r.composeFile());
          case SYSTEMD -> new SystemdLogConfiguration(r.unit());
          case FILE -> new FileLogConfiguration(r.path());
        };
    return new SaveLogSourceCommand(
        r.name(), r.serviceId(), r.connection(), r.sshConnectionId(), r.provider(), c, r.enabled());
  }

  private LogSourceResponse response(LogSource s) {
    return new LogSourceResponse(
        s.id(),
        s.projectId(),
        s.name(),
        s.serviceId(),
        s.connectionType(),
        s.sshConnectionId(),
        s.provider(),
        s.configuration(),
        s.enabled(),
        s.createdAt(),
        s.updatedAt());
  }

  private SshConnectionResponse sshResponse(SshConnection s) {
    return new SshConnectionResponse(
        s.id(),
        s.projectId(),
        s.name(),
        s.host(),
        s.port(),
        s.username(),
        s.createdAt(),
        s.updatedAt());
  }
}
