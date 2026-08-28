package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeTargetCandidate;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeTargetDiscoveryCommand;
import java.util.List;
import java.util.UUID;

public interface DiscoverAgentRuntimeTargets {
  List<AgentRuntimeTargetCandidate> discover(
      UUID projectId, AgentRuntimeTargetDiscoveryCommand command);
}
