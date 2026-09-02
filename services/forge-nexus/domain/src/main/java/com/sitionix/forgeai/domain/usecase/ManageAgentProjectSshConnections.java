package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.*;
import java.util.List;
import java.util.UUID;

public interface ManageAgentProjectSshConnections {
  List<AgentSshConnection> list(UUID projectId);
  AgentSshConnection create(UUID projectId, CreateAgentSshConnectionCommand command);
  void test(UUID projectId, CreateAgentSshConnectionCommand command);
  AgentAssetMetrics metrics(UUID projectId, UUID connectionId);
}
