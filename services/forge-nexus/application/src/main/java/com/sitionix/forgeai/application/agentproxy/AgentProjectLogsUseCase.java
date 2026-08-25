package com.sitionix.forgeai.application.agentproxy;

import com.sitionix.forgeai.domain.model.agentproxy.*;
import com.sitionix.forgeai.domain.port.ForgeAgentClient;
import com.sitionix.forgeai.domain.usecase.ManageAgentProjectLogs;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentProjectLogsUseCase implements ManageAgentProjectLogs {
  private final ForgeAgentClient client;

  public List<AgentLogSource> list(UUID projectId) {
    return client.listProjectLogSources(projectId);
  }

  public AgentLogSource create(UUID projectId, SaveAgentLogSourceCommand command) {
    return client.createProjectLogSource(projectId, command);
  }

  public AgentLogSource update(UUID projectId, UUID sourceId, SaveAgentLogSourceCommand command) {
    return client.updateProjectLogSource(projectId, sourceId, command);
  }

  public void delete(UUID projectId, UUID sourceId) {
    client.deleteProjectLogSource(projectId, sourceId);
  }

  public List<AgentLogTargetCandidate> discover(UUID projectId, AgentLogDiscoveryCommand command) {
    return client.discoverProjectLogTargets(projectId, command);
  }

  public void validate(UUID projectId, SaveAgentLogSourceCommand command) {
    client.validateProjectLogSource(projectId, command);
  }

  public List<AgentSshConnection> listSshConnections(UUID projectId) {
    return client.listProjectSshConnections(projectId);
  }

  public AgentSshConnection createSshConnection(
      UUID projectId, CreateAgentSshConnectionCommand command) {
    return client.createProjectSshConnection(projectId, command);
  }

  public void stream(UUID projectId, List<UUID> sourceIds, int lines, OutputStream output) {
    client.streamProjectLogs(projectId, sourceIds, lines, output);
  }
}
