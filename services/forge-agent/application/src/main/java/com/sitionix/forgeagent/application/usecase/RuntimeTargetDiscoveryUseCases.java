package com.sitionix.forgeagent.application.usecase;

import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.model.RuntimeTargetCandidate;
import com.sitionix.forgeagent.domain.model.ServiceConnectionType;
import com.sitionix.forgeagent.domain.model.SshConnection;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.RuntimeTargetDiscoveryPort;
import com.sitionix.forgeagent.domain.port.SshConnectionRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RuntimeTargetDiscoveryUseCases {
  private final ProjectRepository projects;
  private final SshConnectionRepository connections;
  private final RuntimeTargetDiscoveryPort discovery;

  public List<RuntimeTargetCandidate> discover(
      UUID projectId, RuntimeTargetDiscoveryCommand command) {
    project(projectId);
    if (command == null || command.connection() == null || command.provider() == null) {
      throw new ValidationException("Connection and provider are required");
    }
    if (command.connection() == ServiceConnectionType.LOCAL && command.sshConnectionId() != null) {
      throw new ValidationException("Local runtime discovery cannot reference SSH");
    }
    SshConnection ssh = resolve(projectId, command.connection(), command.sshConnectionId());
    return discovery.discover(ssh, command.provider());
  }

  private Project project(UUID id) {
    return projects
        .findById(id)
        .orElseThrow(() -> new NotFoundException("PROJECT_NOT_FOUND", "Project not found"));
  }

  private SshConnection resolve(UUID projectId, ServiceConnectionType connection, UUID sshId) {
    if (connection == ServiceConnectionType.LOCAL) {
      return null;
    }
    if (sshId == null) {
      throw new ValidationException("SSH connection is required");
    }
    var ssh =
        connections
            .findById(sshId)
            .orElseThrow(
                () ->
                    new NotFoundException("SSH_CONNECTION_NOT_FOUND", "SSH connection not found"));
    if (!ssh.projectId().equals(projectId)) {
      throw new NotFoundException("SSH_CONNECTION_NOT_FOUND", "SSH connection not found");
    }
    return ssh;
  }
}
