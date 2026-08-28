package com.sitionix.forgeagent.api;

import com.sitionix.forgeagent.api.dto.*;
import com.sitionix.forgeagent.application.usecase.*;
import com.sitionix.forgeagent.domain.model.*;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{projectId}/assets")
public class ProjectAssetsController {
  private final ProjectAssetUseCases assets;

  @GetMapping public List<ProjectAssetResponse> list(@PathVariable UUID projectId) {
    return assets.list(projectId).stream().map(this::response).toList();
  }
  @PostMapping public ResponseEntity<ProjectAssetResponse> create(@PathVariable UUID projectId,
      @Valid @RequestBody ProjectAssetRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(response(assets.create(projectId,
        new CreateProjectAssetCommand(request.name(), request.sshConnectionId()))));
  }
  @GetMapping("/{assetId}") public ProjectAssetResponse get(@PathVariable UUID projectId, @PathVariable UUID assetId) {
    return response(assets.get(projectId, assetId));
  }
  @GetMapping("/{assetId}/metrics") public AssetMetrics metrics(@PathVariable UUID projectId, @PathVariable UUID assetId) {
    return assets.metrics(projectId, assetId);
  }
  @GetMapping("/{assetId}/capabilities") public AssetCapabilities capabilities(@PathVariable UUID projectId, @PathVariable UUID assetId) {
    return assets.capabilities(projectId, assetId);
  }
  @GetMapping("/{assetId}/monitoring") public List<LogSourceResponse> monitoring(@PathVariable UUID projectId, @PathVariable UUID assetId) {
    return assets.monitoring(projectId, assetId).stream().map(this::logResponse).toList();
  }
  @PostMapping("/{assetId}/monitoring") public ResponseEntity<LogSourceResponse> monitor(@PathVariable UUID projectId,
      @PathVariable UUID assetId, @Valid @RequestBody AssetMonitoringRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(logResponse(assets.monitor(projectId, assetId,
        request.provider(), request.name(), request.target(), request.enabled())));
  }
  @DeleteMapping("/{assetId}") @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID projectId, @PathVariable UUID assetId) { assets.delete(projectId, assetId); }
  private ProjectAssetResponse response(ProjectAsset a) { return new ProjectAssetResponse(a.id(), a.projectId(), a.name(), a.sshConnectionId(), a.createdAt(), a.updatedAt()); }
  private LogSourceResponse logResponse(LogSource s) {
    LogProviderConfigurationResponse c = switch (s.configuration()) {
      case DockerLogConfiguration d -> new LogProviderConfigurationResponse(d.container(), d.composeService(), d.composeFile(), null, null, null);
      case SystemdLogConfiguration systemd -> new LogProviderConfigurationResponse(null, null, null, systemd.mode(), systemd.unit(), null);
      case FileLogConfiguration f -> new LogProviderConfigurationResponse(null, null, null, null, null, f.path());
    };
    return new LogSourceResponse(s.id(), s.projectId(), s.name(), s.serviceId(), s.connectionType(), s.sshConnectionId(), s.provider(), c, s.enabled(), s.createdAt(), s.updatedAt());
  }
}
