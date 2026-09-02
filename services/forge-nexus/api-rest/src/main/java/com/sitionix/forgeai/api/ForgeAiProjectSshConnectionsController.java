package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.agentproxy.*;
import com.sitionix.forgeai.domain.usecase.ManageAgentProjectSshConnections;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/infrastructure/agents/projects/{projectId}/ssh-connections")
public class ForgeAiProjectSshConnectionsController {
  private final ManageAgentProjectSshConnections ssh;
  private final AgentProxyApiMapper mapper;

  @GetMapping public List<AgentSshConnectionResponse> list(@PathVariable UUID projectId) {
    return ssh.list(projectId).stream().map(mapper::toResponse).toList();
  }
  @PostMapping @ResponseStatus(HttpStatus.CREATED)
  public AgentSshConnectionResponse create(@PathVariable UUID projectId,
      @Valid @RequestBody AgentSshConnectionRequest request) {
    return mapper.toResponse(ssh.create(projectId, mapper.toCommand(request)));
  }
  @PostMapping("/test") @ResponseStatus(HttpStatus.NO_CONTENT)
  public void test(@PathVariable UUID projectId, @Valid @RequestBody AgentSshConnectionRequest request) {
    ssh.test(projectId, mapper.toCommand(request));
  }
  @GetMapping("/{connectionId}/metrics")
  public AgentAssetMetricsResponse metrics(@PathVariable UUID projectId, @PathVariable UUID connectionId) {
    return mapper.toResponse(ssh.metrics(projectId, connectionId));
  }
  @GetMapping("/{connectionId}/service-metrics")
  public AgentServiceMetricsResponse serviceMetrics(@PathVariable UUID projectId, @PathVariable UUID connectionId) {
    return mapper.toResponse(ssh.serviceMetrics(projectId, connectionId));
  }
}
