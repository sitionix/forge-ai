package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.agentproxy.*;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;

public interface ManageAgentProjectLogs {
  List<AgentLogSource> list(UUID projectId);

  AgentLogSource create(UUID projectId, SaveAgentLogSourceCommand command);

  AgentLogSource update(UUID projectId, UUID sourceId, SaveAgentLogSourceCommand command);

  void delete(UUID projectId, UUID sourceId);

  List<AgentLogTargetCandidate> discover(UUID projectId, AgentLogDiscoveryCommand command);

  void validate(UUID projectId, SaveAgentLogSourceCommand command);

  List<AgentSshConnection> listSshConnections(UUID projectId);

  AgentSshConnection createSshConnection(UUID projectId, CreateAgentSshConnectionCommand command);

  void stream(UUID projectId, List<UUID> sourceIds, int lines, OutputStream output);
}
