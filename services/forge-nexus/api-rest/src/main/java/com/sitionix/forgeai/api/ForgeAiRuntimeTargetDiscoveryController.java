package com.sitionix.forgeai.api;

import com.sitionix.forgeai.api.agentproxy.AgentRuntimeTargetCandidateResponse;
import com.sitionix.forgeai.api.agentproxy.AgentRuntimeTargetDiscoveryRequest;
import com.sitionix.forgeai.api.agentproxy.AgentProxyApiMapper;
import com.sitionix.forgeai.domain.usecase.DiscoverAgentRuntimeTargets;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/infrastructure/agents/projects/{projectId}/runtime-targets")
public class ForgeAiRuntimeTargetDiscoveryController {
  private final DiscoverAgentRuntimeTargets discovery;
  private final AgentProxyApiMapper mapper;

  @PostMapping("/discover")
  public List<AgentRuntimeTargetCandidateResponse> discover(
      @PathVariable UUID projectId,
      @Valid @RequestBody AgentRuntimeTargetDiscoveryRequest request) {
    return discovery.discover(projectId, mapper.toCommand(request)).stream()
        .map(mapper::toResponse)
        .toList();
  }
}
