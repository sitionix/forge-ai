package com.sitionix.forgeai.application.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.*;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.ManageAgentProjectSshConnections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentProjectSshConnectionsUseCase implements ManageAgentProjectSshConnections {
  private final ForgeAgentClient client;
  public List<AgentSshConnection> list(UUID projectId) { return client.listProjectSshConnections(projectId); }
  public AgentSshConnection create(UUID projectId, CreateAgentSshConnectionCommand command) {
    return client.createProjectSshConnection(projectId, command);
  }
  public void test(UUID projectId, CreateAgentSshConnectionCommand command) {
    client.testProjectSshConnection(projectId, command);
  }
  public AgentAssetMetrics metrics(UUID projectId, UUID connectionId) {
    return client.getProjectSshConnectionMetrics(projectId, connectionId);
  }
  public AgentServiceMetricsSnapshot serviceMetrics(UUID projectId, UUID connectionId) {
    return client.getProjectSshConnectionServiceMetrics(projectId, connectionId);
  }
}
