package com.sitionix.forgeai.application.usecase.agentproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.application.agentproxy.AgentRuntimeTargetDiscoveryUseCase;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeTargetCandidate;
import com.sitionix.forgeai.domain.model.agentproxy.AgentRuntimeTargetDiscoveryCommand;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentRuntimeTargetDiscoveryUseCaseTest {
  @Test
  void delegatesToTypedAgentClientWithoutAddingSemantics() {
    ForgeAgentClient client = mock(ForgeAgentClient.class);
    var useCase = new AgentRuntimeTargetDiscoveryUseCase(client);
    UUID projectId = UUID.randomUUID();
    var command = new AgentRuntimeTargetDiscoveryCommand("SSH", UUID.randomUUID(), "SYSTEMD");
    var candidate = new AgentRuntimeTargetCandidate("forge-agent.service", "SYSTEMD");
    when(client.discoverProjectRuntimeTargets(projectId, command)).thenReturn(List.of(candidate));

    assertThat(useCase.discover(projectId, command)).containsExactly(candidate);

    verify(client).discoverProjectRuntimeTargets(projectId, command);
  }
}
