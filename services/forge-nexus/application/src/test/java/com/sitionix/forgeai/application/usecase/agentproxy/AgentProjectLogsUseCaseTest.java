package com.sitionix.forgeai.application.usecase.agentproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.application.agentproxy.AgentProjectLogsUseCase;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogConnectionType;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogDiscoveryCommand;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogProviderType;
import com.sitionix.forgeai.domain.port.AgentLogStream;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentProjectLogsUseCaseTest {

  @Test
  void delegatesDiscoveryAndEstablishedStreamingToTypedAgentClient() {
    final ForgeAgentClient client = mock(ForgeAgentClient.class);
    final AgentProjectLogsUseCase useCase = new AgentProjectLogsUseCase(client);
    final UUID projectId = UUID.randomUUID();
    final UUID sourceId = UUID.randomUUID();
    final AgentLogDiscoveryCommand discovery =
        new AgentLogDiscoveryCommand(
            AgentLogConnectionType.LOCAL, null, AgentLogProviderType.DOCKER, null);
    final AgentLogStream stream = mock(AgentLogStream.class);
    when(client.discoverProjectLogTargets(projectId, discovery)).thenReturn(List.of());
    when(client.openProjectLogsStream(projectId, List.of(sourceId), 100)).thenReturn(stream);

    assertThat(useCase.discover(projectId, discovery)).isEmpty();
    assertThat(useCase.openStream(projectId, List.of(sourceId), 100)).isSameAs(stream);

    verify(client).discoverProjectLogTargets(projectId, discovery);
    verify(client).openProjectLogsStream(projectId, List.of(sourceId), 100);
  }
}
