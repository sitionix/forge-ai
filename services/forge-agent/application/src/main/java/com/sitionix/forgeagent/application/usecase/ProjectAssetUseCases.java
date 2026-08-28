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
