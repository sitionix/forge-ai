package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProjectAssetUseCases {
  private final ProjectRepository projects;
  private final ProjectAssetRepository assets;
  private final SshConnectionRepository connections;
  private final LogSourceRepository logSources;
  private final AssetInspectionPort inspection;
  private final RuntimeTargetDiscoveryPort discovery;
  private final Clock clock;

  @Transactional(readOnly = true)
  public List<ProjectAsset> list(UUID projectId) {
    project(projectId);
    return assets.findByProjectId(projectId);
  }

  @Transactional(readOnly = true)
  public ProjectAsset get(UUID projectId, UUID assetId) { return owned(projectId, assetId); }

  public ProjectAsset create(UUID projectId, CreateProjectAssetCommand command) {
    project(projectId);
    if (command.name() == null || command.name().isBlank())
      throw new ValidationException("Resource name is required");
    SshConnection connection = connection(projectId, command.sshConnectionId());
    // Saving a resource requires a live, inspectable source. Capability discovery is intentionally eager.
    inspection.capabilities(connection);
    var now = clock.instant();
    return assets.save(new ProjectAsset(UUID.randomUUID(), projectId, command.name().strip(),
        connection.id(), now, now));
  }

  @Transactional(readOnly = true)
  public AssetMetrics metrics(UUID projectId, UUID assetId) {
    var asset = owned(projectId, assetId);
    return inspection.metrics(connection(projectId, asset.sshConnectionId()));
  }

  @Transactional(readOnly = true)
  public AssetCapabilities capabilities(UUID projectId, UUID assetId) {
    var asset = owned(projectId, assetId);
    return inspection.capabilities(connection(projectId, asset.sshConnectionId()));
  }

  @Transactional(readOnly = true)
  public List<LogSource> monitoring(UUID projectId, UUID assetId) {
    owned(projectId, assetId);
    return logSources.findByProjectIdAndAssetId(projectId, assetId);
  }

  public LogSource monitor(UUID projectId, UUID assetId, LogProviderType provider, String name,
      String target, boolean enabled) {
    var asset = owned(projectId, assetId);
    if (provider == null || name == null || name.isBlank() || target == null || target.isBlank())
      throw new ValidationException("Monitoring provider, name, and target are required");
    var connection = connection(projectId, asset.sshConnectionId());
    LogProviderConfiguration configuration;
    if (provider == LogProviderType.FILE) {
      if (!target.startsWith("/") || target.indexOf('\0') >= 0) throw new ValidationException("File path is invalid");
      configuration = new FileLogConfiguration(target);
    } else {
      var runtimeProvider = provider == LogProviderType.DOCKER ? ServiceRuntimeProvider.DOCKER : ServiceRuntimeProvider.SYSTEMD;
      boolean found = discovery.discover(connection, runtimeProvider).stream().anyMatch(candidate -> candidate.id().equals(target));
      if (!found) throw new ValidationException("Monitoring target was not discovered on this Resource");
      configuration = provider == LogProviderType.DOCKER
          ? new DockerLogConfiguration(target, null, null)
          : new SystemdLogConfiguration(SystemdTargetMode.UNIT, target);
    }
    var now = clock.instant();
    return logSources.save(new LogSource(UUID.randomUUID(), projectId, name.strip(), LogSourceOwnerType.ASSET, null, assetId,
        LogConnectionType.SSH, null, provider, configuration, enabled, now, now));
  }

  public record MonitoringTarget(LogProviderType provider, String target) {}

  public List<LogSource> replaceMonitoring(UUID projectId, UUID assetId, List<MonitoringTarget> requested) {
    var asset = owned(projectId, assetId);
    var connection = connection(projectId, asset.sshConnectionId());
    var existing = logSources.findByProjectIdAndAssetId(projectId, assetId);
    var existingByKey = new LinkedHashMap<String, LogSource>();
    existing.forEach(source -> existingByKey.put(monitoringKey(source.provider(), monitoringTarget(source)), source));

    var desired = new LinkedHashMap<String, MonitoringTarget>();
    for (var item : requested == null ? List.<MonitoringTarget>of() : requested) {
      if (item == null || item.provider() == null || item.target() == null || item.target().isBlank())
        throw new ValidationException("Monitoring provider and target are required");
      var target = item.target().strip();
      var normalized = new MonitoringTarget(item.provider(), target);
      if (desired.putIfAbsent(monitoringKey(item.provider(), target), normalized) != null)
        throw new ValidationException("Duplicate monitoring target: " + item.provider() + ":" + target);
      if (item.provider() == LogProviderType.FILE) {
        if (!target.startsWith("/") || target.indexOf('\0') >= 0)
          throw new ValidationException("File target must be an absolute POSIX path");
      } else if (!existingByKey.containsKey(monitoringKey(item.provider(), target))) {
        var runtimeProvider = item.provider() == LogProviderType.DOCKER
            ? ServiceRuntimeProvider.DOCKER : ServiceRuntimeProvider.SYSTEMD;
        if (discovery.discover(connection, runtimeProvider).stream().noneMatch(candidate -> candidate.id().equals(target)))
          throw new ValidationException("New monitoring target was not discovered on this Resource: " + target);
      }
    }

    var now = clock.instant();
    var result = new ArrayList<LogSource>();
    for (var entry : desired.entrySet()) {
      var unchanged = existingByKey.get(entry.getKey());
      if (unchanged != null) {
        result.add(unchanged);
        continue;
      }
      var target = entry.getValue();
      LogProviderConfiguration configuration = switch (target.provider()) {
        case FILE -> new FileLogConfiguration(target.target());
        case DOCKER -> new DockerLogConfiguration(target.target(), null, null);
        case SYSTEMD -> new SystemdLogConfiguration(SystemdTargetMode.UNIT, target.target());
      };
      result.add(logSources.save(new LogSource(UUID.randomUUID(), projectId, target.target(),
          LogSourceOwnerType.ASSET, null, assetId, LogConnectionType.SSH, null,
          target.provider(), configuration, true, now, now)));
    }
    existing.stream().filter(source -> !desired.containsKey(monitoringKey(source.provider(), monitoringTarget(source))))
        .forEach(logSources::delete);
    return result;
  }

  private String monitoringTarget(LogSource source) {
    return switch (source.configuration()) {
      case DockerLogConfiguration docker -> docker.container();
      case SystemdLogConfiguration systemd -> systemd.unit();
      case FileLogConfiguration file -> file.path();
    };
  }

  private String monitoringKey(LogProviderType provider, String target) { return provider + "\0" + target; }

  public void delete(UUID projectId, UUID assetId) {
    var asset = owned(projectId, assetId);
    logSources.findByProjectIdAndAssetId(projectId, assetId).forEach(logSources::delete);
    assets.delete(asset);
  }

  private void project(UUID id) {
    if (projects.findById(id).isEmpty()) throw new NotFoundException("PROJECT_NOT_FOUND", "Project not found");
  }

  private ProjectAsset owned(UUID projectId, UUID assetId) {
    project(projectId);
    var asset = assets.findById(assetId)
        .orElseThrow(() -> new NotFoundException("ASSET_NOT_FOUND", "Resource not found"));
    if (!asset.projectId().equals(projectId)) throw new NotFoundException("ASSET_NOT_FOUND", "Resource not found");
    return asset;
  }

  private SshConnection connection(UUID projectId, UUID id) {
    if (id == null) throw new ValidationException("SSH connection is required");
    var connection = connections.findById(id)
        .orElseThrow(() -> new NotFoundException("SSH_CONNECTION_NOT_FOUND", "SSH connection not found"));
    if (!connection.projectId().equals(projectId))
      throw new NotFoundException("SSH_CONNECTION_NOT_FOUND", "SSH connection not found");
    return connection;
  }
}
