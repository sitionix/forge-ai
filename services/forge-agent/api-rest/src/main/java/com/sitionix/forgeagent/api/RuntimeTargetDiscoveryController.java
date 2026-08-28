package com.sitionix.forgeagent.api;

import com.sitionix.forgeagent.api.dto.RuntimeTargetCandidateResponse;
import com.sitionix.forgeagent.api.dto.RuntimeTargetDiscoveryRequest;
import com.sitionix.forgeagent.application.usecase.RuntimeTargetDiscoveryCommand;
import com.sitionix.forgeagent.application.usecase.RuntimeTargetDiscoveryUseCases;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RuntimeTargetDiscoveryController {
  private final RuntimeTargetDiscoveryUseCases discovery;
  private final ForgeAgentApiMapper mapper;

  @PostMapping("/api/v1/projects/{projectId}/runtime-targets/discover")
  public List<RuntimeTargetCandidateResponse> discover(
      @PathVariable UUID projectId, @Valid @RequestBody RuntimeTargetDiscoveryRequest request) {
    return discovery
        .discover(
            projectId,
            new RuntimeTargetDiscoveryCommand(
                request.connection(), request.sshConnectionId(), request.provider()))
        .stream()
        .map(mapper::toResponse)
        .toList();
  }
}
