package com.sitionix.forgeai.application.usecase.agentproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.application.agentproxy.AgentProjectAssetsUseCase;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogProviderType;
import com.sitionix.forgeai.domain.model.agentproxy.AgentLogSource;
import com.sitionix.forgeai.domain.model.agentproxy.ReplaceAgentAssetMonitoringCommand;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentProjectAssetsUseCaseTest {
  @Test
  void proxiesDesiredMonitoringStateAsOneTypedOperation() {
    var client = mock(ForgeAgentClient.class);
    var useCase = new AgentProjectAssetsUseCase(client);
    var projectId = UUID.randomUUID();
    var assetId = UUID.randomUUID();
    var command = new ReplaceAgentAssetMonitoringCommand(List.of(
        new ReplaceAgentAssetMonitoringCommand.Target(AgentLogProviderType.SYSTEMD, "ancestor.service"),
        new ReplaceAgentAssetMonitoringCommand.Target(AgentLogProviderType.FILE, "/var/log/app.log")));
    List<AgentLogSource> authoritative = List.of();
    when(client.replaceProjectAssetMonitoring(projectId, assetId, command)).thenReturn(authoritative);

    assertThat(useCase.replaceMonitoring(projectId, assetId, command)).isSameAs(authoritative);
    verify(client).replaceProjectAssetMonitoring(projectId, assetId, command);
  }
}
