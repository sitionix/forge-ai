package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.exception.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class LogSourceUseCases {
  private static final Pattern DOCKER_TARGET = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}");
  private static final Pattern SYSTEMD_UNIT =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9:_.@\\-]{0,253}\\.[A-Za-z0-9_.@-]+");
  private final ProjectRepository projects;
  private final LogSourceRepository sources;
  private final ProjectServiceRepository services;
  private final SshConnectionRepository connections;
  private final DockerLogPort docker;
  private final RemoteLogPort remote;
  private final RuntimeTargetDiscoveryUseCases runtimeTargets;
  private final ProjectRepositoryLinkRepository repositories;
  private final LocalProjectWorkspacePort workspaces;
  private final GitRepositoryPort git;
  private final Clock clock;

  @Transactional(readOnly = true)
  public List<LogSource> list(UUID projectId) {
    project(projectId);
    return sources.findByProjectId(projectId);
  }

  @Transactional(readOnly = true)
  public List<LogSource> list(UUID projectId, UUID serviceId) {
    service(projectId, serviceId);
    return sources.findByProjectIdAndServiceId(projectId, serviceId);
  }

  @Transactional(readOnly = true)
  public List<LogSource> requireEnabled(UUID projectId, List<UUID> sourceIds) {
    project(projectId);
    if (sourceIds == null || sourceIds.isEmpty())
      throw new ValidationException("At least one log source is required");
    return sourceIds.stream()
        .distinct()
        .map(id -> owned(projectId, id))
        .peek(
            source -> {
              if (!source.enabled())
                throw new ValidationException("Log source is disabled: " + source.name());
            })
        .toList();
  }

  public LogSource create(UUID projectId, SaveLogSourceCommand c) {
    project(projectId);
    validate(projectId, c);
    var now = clock.instant();
    return sources.save(
        new LogSource(
            UUID.randomUUID(),
            projectId,
            c.name().strip(),
            c.serviceId(),
            c.connectionType(),
            c.sshConnectionId(),
            c.provider(),
            c.configuration(),
            c.enabled(),
            now,
            now));
  }

  public LogSource update(UUID projectId, UUID id, SaveLogSourceCommand c) {
    var old = owned(projectId, id);
    validate(projectId, c);
    return sources.save(
        new LogSource(
            id,
            projectId,
            c.name().strip(),
            c.serviceId(),
            c.connectionType(),
            c.sshConnectionId(),
            c.provider(),
            c.configuration(),
            c.enabled(),
            old.createdAt(),
            clock.instant()));
  }

  public void delete(UUID projectId, UUID id) {
    sources.delete(owned(projectId, id));
  }

  @Transactional(readOnly = true)
  public List<LogTargetCandidate> discover(
      UUID projectId,
      LogConnectionType connection,
      UUID sshId,
      LogProviderType provider,
      UUID repositoryId) {
    project(projectId);
    if (connection == null || provider == null)
      throw new ValidationException("Connection and provider are required");
    if (connection == LogConnectionType.LOCAL && provider != LogProviderType.DOCKER)
      throw new ValidationException("Only Docker supports a local connection");
    if (connection == LogConnectionType.SSH && repositoryId != null)
      throw new ValidationException("Compose repository discovery is available only locally");
    SshConnection ssh = resolve(projectId, connection, sshId);
    if (provider != LogProviderType.DOCKER) {
      return runtimeTargets.discover(projectId, runtimeCommand(connection, sshId, provider)).stream()
          .map(this::logCandidate)
          .toList();
    }
    var candidates =
        new ArrayList<>(
            runtimeTargets.discover(projectId, runtimeCommand(connection, sshId, provider)).stream()
                .map(this::logCandidate)
                .toList());
    if (connection == LogConnectionType.LOCAL && repositoryId != null)
      candidates.addAll(discoverCompose(projectId, repositoryId));
    return List.copyOf(candidates);
  }

  @Transactional(readOnly = true)
  public void validateTarget(UUID projectId, SaveLogSourceCommand c) {
    project(projectId);
    validate(projectId, c);
    SshConnection ssh = resolve(projectId, c.connectionType(), c.sshConnectionId());
    if (c.provider() == LogProviderType.DOCKER) {
      var d = (DockerLogConfiguration) c.configuration();
      docker.validate(d.container(), d.composeService(), d.composeFile(), ssh);
    } else remote.validate(ssh, c.provider(), c.configuration());
  }

  @Transactional(readOnly = true)
  public LogStream stream(UUID projectId, UUID id, int lines) {
    return stream(projectId, owned(projectId, id), lines);
  }

  @Transactional(readOnly = true)
  public LogStream stream(UUID projectId, LogSource s, int lines) {
    if (lines < 1 || lines > 10_000)
      throw new ValidationException("Initial line count must be between 1 and 10000");
    if (!s.projectId().equals(projectId))
      throw new NotFoundException("LOG_SOURCE_NOT_FOUND", "Log source not found");
    if (!s.enabled()) throw new ValidationException("Log source is disabled");
    SshConnection ssh = resolve(projectId, s.connectionType(), s.sshConnectionId());
    if (s.provider() == LogProviderType.DOCKER) {
      var d = (DockerLogConfiguration) s.configuration();
      return docker.stream(d.container(), d.composeService(), d.composeFile(), lines, ssh);
    }
    return remote.stream(ssh, s.provider(), s.configuration(), lines);
  }

  private void validate(UUID projectId, SaveLogSourceCommand c) {
    if (c.name() == null || c.name().isBlank())
      throw new ValidationException("Log source name is required");
    if (c.serviceId() != null) service(projectId, c.serviceId());
    if (c.connectionType() == null || c.provider() == null)
      throw new ValidationException("Connection and provider are required");
    if (c.connectionType() == LogConnectionType.LOCAL && c.sshConnectionId() != null)
      throw new ValidationException("Local sources cannot reference SSH");
    if (c.connectionType() == LogConnectionType.SSH)
      resolve(projectId, c.connectionType(), c.sshConnectionId());
    if (c.connectionType() == LogConnectionType.LOCAL && c.provider() != LogProviderType.DOCKER)
      throw new ValidationException("Only Docker supports a local connection");
    if (c.configuration() == null || !matches(c.provider(), c.configuration()))
      throw new ValidationException("Provider configuration does not match provider");
    validateConfiguration(c.provider(), c.configuration());
  }

  private void validateConfiguration(
      LogProviderType provider, LogProviderConfiguration configuration) {
    switch (provider) {
      case DOCKER -> {
        var d = (DockerLogConfiguration) configuration;
        boolean container = nonblank(d.container());
        boolean compose = nonblank(d.composeService());
        if (container == compose)
          throw new ValidationException(
              "Configure exactly one Docker container or Compose service");
        if (container && !DOCKER_TARGET.matcher(d.container()).matches())
          throw new ValidationException("Docker container is invalid");
        if (compose && !DOCKER_TARGET.matcher(d.composeService()).matches())
          throw new ValidationException("Compose service is invalid");
        if (compose && !safePath(d.composeFile()))
          throw new ValidationException("Compose file is required or invalid");
      }
      case SYSTEMD -> {
        var systemd = (SystemdLogConfiguration) configuration;
        if (systemd.mode() == null)
          throw new ValidationException("Systemd target mode is required");
        String unit = systemd.unit();
        if (systemd.mode() == SystemdTargetMode.UNIT
            && (!nonblank(unit) || !SYSTEMD_UNIT.matcher(unit).matches()))
          throw new ValidationException("Systemd unit is required or invalid");
        if (systemd.mode() == SystemdTargetMode.FULL_JOURNAL && nonblank(unit))
          throw new ValidationException("Full journal cannot specify a systemd unit");
      }
      case FILE -> {
        if (!safePath(((FileLogConfiguration) configuration).path()))
          throw new ValidationException("File path is required or invalid");
      }
    }
  }

  private boolean matches(LogProviderType p, LogProviderConfiguration c) {
    return (p == LogProviderType.DOCKER && c instanceof DockerLogConfiguration)
        || (p == LogProviderType.SYSTEMD && c instanceof SystemdLogConfiguration)
        || (p == LogProviderType.FILE && c instanceof FileLogConfiguration);
  }

  private Project project(UUID id) {
    return projects
        .findById(id)
        .orElseThrow(() -> new NotFoundException("PROJECT_NOT_FOUND", "Project not found"));
  }

  private LogSource owned(UUID p, UUID id) {
    project(p);
    var s =
        sources
            .findById(id)
            .orElseThrow(
                () -> new NotFoundException("LOG_SOURCE_NOT_FOUND", "Log source not found"));
    if (!s.projectId().equals(p))
      throw new NotFoundException("LOG_SOURCE_NOT_FOUND", "Log source not found");
    return s;
  }

  private ProjectService service(UUID projectId, UUID id) {
    var service = services.findById(id).orElseThrow(() -> new NotFoundException("SERVICE_NOT_FOUND", "Service not found"));
    if (!service.projectId().equals(projectId)) throw new NotFoundException("SERVICE_NOT_FOUND", "Service not found");
    return service;
  }

  private SshConnection resolve(UUID p, LogConnectionType type, UUID id) {
    if (type == LogConnectionType.LOCAL) return null;
    if (id == null) throw new ValidationException("SSH connection is required");
    var c =
        connections
            .findById(id)
            .orElseThrow(
                () ->
                    new NotFoundException("SSH_CONNECTION_NOT_FOUND", "SSH connection not found"));
    if (!c.projectId().equals(p))
      throw new NotFoundException("SSH_CONNECTION_NOT_FOUND", "SSH connection not found");
    return c;
  }

  private List<LogTargetCandidate> discoverCompose(UUID projectId, UUID repositoryId) {
    var repository =
        repositories
            .findById(repositoryId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "PROJECT_REPOSITORY_NOT_FOUND", "Project repository not found"));
    if (!repository.projectId().equals(projectId))
      throw new NotFoundException("PROJECT_REPOSITORY_NOT_FOUND", "Project repository not found");
    String name = git.resolveRepositoryName(repository.remoteUrl());
    var state =
        workspaces.resolveRepositoryWorkspaceState(
            projectId, new ProjectRepositoryWorkspaceReference(repository.id(), name));
    if (state == null || !state.cloned())
      throw new ValidationException("Project repository is not cloned");
    return docker.discoverComposeServices(state.path(), null);
  }

  private RuntimeTargetDiscoveryCommand runtimeCommand(
      LogConnectionType connection, UUID sshId, LogProviderType provider) {
    return new RuntimeTargetDiscoveryCommand(
        connection == LogConnectionType.LOCAL ? ServiceConnectionType.LOCAL : ServiceConnectionType.SSH,
        sshId,
        provider == LogProviderType.DOCKER
            ? ServiceRuntimeProvider.DOCKER
            : ServiceRuntimeProvider.SYSTEMD);
  }

  private LogTargetCandidate logCandidate(RuntimeTargetCandidate target) {
    return new LogTargetCandidate(
        target.id(),
        target.label(),
        switch (target.status()) {
          case RUNNING -> LogTargetStatus.RUNNING;
          case STOPPED -> LogTargetStatus.STOPPED;
          case AVAILABLE -> LogTargetStatus.AVAILABLE;
        },
        target.image(),
        target.composeProject(),
        target.composeService(),
        null,
        false);
  }

  private boolean nonblank(String value) {
    return value != null && !value.isBlank();
  }

  private boolean safePath(String value) {
    return nonblank(value)
        && value.indexOf('\0') < 0
        && value.indexOf('\n') < 0
        && value.indexOf('\r') < 0;
  }
}
