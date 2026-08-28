package com.sitionix.forgeai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.api.agentproxy.AgentProxyApiMapper;
import com.sitionix.forgeai.api.agentproxy.AgentRuntimeTargetCandidateResponse;
import com.sitionix.forgeai.api.agentproxy.AgentRuntimeTargetDiscoveryRequest;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeTargetCandidate;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeTargetDiscoveryCommand;
import com.sitionix.forgeai.domain.usecase.DiscoverAgentRuntimeTargets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ForgeAiRuntimeTargetDiscoveryControllerTest {
  @Test
  void delegatesTypedDiscoveryRequestThroughMapperAndUseCase() {
    DiscoverAgentRuntimeTargets discovery = mock(DiscoverAgentRuntimeTargets.class);
    AgentProxyApiMapper mapper = mock(AgentProxyApiMapper.class);
    var controller = new ForgeAiRuntimeTargetDiscoveryController(discovery, mapper);
    UUID projectId = UUID.randomUUID();
    UUID sshId = UUID.randomUUID();
    var request = new AgentRuntimeTargetDiscoveryRequest("SSH", sshId, "SYSTEMD");
    var command = new AgentRuntimeTargetDiscoveryCommand("SSH", sshId, "SYSTEMD");
    var candidate = new AgentRuntimeTargetCandidate("forge-agent.service", "SYSTEMD");
    var response = new AgentRuntimeTargetCandidateResponse("forge-agent.service", "SYSTEMD");
    when(mapper.toCommand(request)).thenReturn(command);
    when(discovery.discover(projectId, command)).thenReturn(List.of(candidate));
    when(mapper.toResponse(candidate)).thenReturn(response);

    assertThat(controller.discover(projectId, request)).containsExactly(response);

    verify(discovery).discover(projectId, command);
  }
}
