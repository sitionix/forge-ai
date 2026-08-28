package com.sitionix.forgeai.application.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeTargetCandidate;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeTargetDiscoveryCommand;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.DiscoverAgentRuntimeTargets;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentRuntimeTargetDiscoveryUseCase implements DiscoverAgentRuntimeTargets {
  private final ForgeAgentClient client;

  @Override
  public List<AgentRuntimeTargetCandidate> discover(
      UUID projectId, AgentRuntimeTargetDiscoveryCommand command) {
    return client.discoverProjectRuntimeTargets(projectId, command);
  }
}
