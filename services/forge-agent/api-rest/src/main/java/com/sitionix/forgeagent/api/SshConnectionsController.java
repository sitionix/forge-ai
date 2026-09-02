package com.sitionix.forgeagent.api;

import com.sitionix.forgeagent.api.dto.SshConnectionRequest;
import com.sitionix.forgeagent.api.dto.SshConnectionResponse;
import com.sitionix.forgeagent.application.usecase.SshConnectionUseCases;
import com.sitionix.forgeagent.domain.model.AssetMetrics;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{projectId}/ssh-connections")
public class SshConnectionsController {
  private final SshConnectionUseCases ssh;
  private final ForgeAgentApiMapper mapper;

  @GetMapping
  public List<SshConnectionResponse> list(@PathVariable UUID projectId) {
    return ssh.list(projectId).stream().map(mapper::toResponse).toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public SshConnectionResponse create(@PathVariable UUID projectId,
      @Valid @RequestBody SshConnectionRequest request) {
    return mapper.toResponse(ssh.create(projectId, mapper.toCommand(request)));
  }

  @PostMapping("/test")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void test(@PathVariable UUID projectId, @Valid @RequestBody SshConnectionRequest request) {
    ssh.test(projectId, mapper.toCommand(request));
  }

  @GetMapping("/{connectionId}/metrics")
  public AssetMetrics metrics(@PathVariable UUID projectId, @PathVariable UUID connectionId) {
    return ssh.metrics(projectId, connectionId);
  }

  @GetMapping("/{connectionId}/service-metrics")
  public com.sitionix.forgeagent.domain.model.ServiceMetricsSnapshot serviceMetrics(
      @PathVariable UUID projectId, @PathVariable UUID connectionId) {
    return ssh.serviceMetrics(projectId, connectionId);
  }

  @GetMapping("/{connectionId}/service-metrics/{unit}/processes")
  public com.sitionix.forgeagent.domain.model.ServiceProcessMetricsSnapshot serviceProcesses(
      @PathVariable UUID projectId, @PathVariable UUID connectionId, @PathVariable String unit,
      @RequestParam(defaultValue = "cpu") String sort) {
    final com.sitionix.forgeagent.domain.model.ProcessMetricsSort typedSort;
    try {
      typedSort = com.sitionix.forgeagent.domain.model.ProcessMetricsSort.valueOf(sort.toUpperCase(java.util.Locale.ROOT));
    } catch (RuntimeException ignored) {
      throw new com.sitionix.forgeagent.domain.exception.ValidationException("Process metric sort is invalid");
    }
    return ssh.serviceProcesses(projectId, connectionId, unit, typedSort);
  }
}
