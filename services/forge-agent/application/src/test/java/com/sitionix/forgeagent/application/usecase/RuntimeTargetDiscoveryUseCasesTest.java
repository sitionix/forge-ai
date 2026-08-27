package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.model.RuntimeTargetCandidate;
import com.sitionix.forgeagent.domain.model.RuntimeTargetStatus;
import com.sitionix.forgeagent.domain.model.ServiceConnectionType;
import com.sitionix.forgeagent.domain.model.ServiceRuntimeProvider;
import com.sitionix.forgeagent.domain.model.SshConnection;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.RuntimeTargetDiscoveryPort;
import com.sitionix.forgeagent.domain.port.SshConnectionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class RuntimeTargetDiscoveryUseCasesTest {
  @Mock ProjectRepository projects;
  @Mock SshConnectionRepository connections;
  @Mock RuntimeTargetDiscoveryPort discovery;
  UUID projectId = UUID.randomUUID();
  RuntimeTargetDiscoveryUseCases useCases;

  @BeforeEach
  void setUp() {
    useCases = new RuntimeTargetDiscoveryUseCases(projects, connections, discovery);
    when(projects.findById(projectId))
        .thenReturn(Optional.of(new Project(projectId, "Project", "project", Instant.EPOCH, Instant.EPOCH)));
  }

  @Test
  void localDockerDiscoveryDelegatesToRuntimePort() {
    var candidate = candidate("forge-postgres", ServiceRuntimeProvider.DOCKER);
    when(discovery.discover(null, ServiceRuntimeProvider.DOCKER)).thenReturn(List.of(candidate));

    assertThat(useCases.discover(projectId,
        new RuntimeTargetDiscoveryCommand(ServiceConnectionType.LOCAL, null, ServiceRuntimeProvider.DOCKER)))
        .containsExactly(candidate);
  }

  @Test
  void localSystemdDiscoveryDelegatesToRuntimePort() {
    var candidate = candidate("forge-agent.service", ServiceRuntimeProvider.SYSTEMD);
    when(discovery.discover(null, ServiceRuntimeProvider.SYSTEMD)).thenReturn(List.of(candidate));

    assertThat(useCases.discover(projectId,
        new RuntimeTargetDiscoveryCommand(ServiceConnectionType.LOCAL, null, ServiceRuntimeProvider.SYSTEMD)))
        .containsExactly(candidate);
  }

  @Test
  void sshDockerAndSystemdDiscoveryResolveOwnedConnection() {
    UUID sshId = UUID.randomUUID();
    var ssh = ssh(sshId, projectId);
    when(connections.findById(sshId)).thenReturn(Optional.of(ssh));

    useCases.discover(projectId,
        new RuntimeTargetDiscoveryCommand(ServiceConnectionType.SSH, sshId, ServiceRuntimeProvider.DOCKER));
    useCases.discover(projectId,
        new RuntimeTargetDiscoveryCommand(ServiceConnectionType.SSH, sshId, ServiceRuntimeProvider.SYSTEMD));

    verify(discovery).discover(ssh, ServiceRuntimeProvider.DOCKER);
    verify(discovery).discover(ssh, ServiceRuntimeProvider.SYSTEMD);
  }

  @Test
  void rejectsSshProfileFromAnotherProject() {
    UUID sshId = UUID.randomUUID();
    when(connections.findById(sshId)).thenReturn(Optional.of(ssh(sshId, UUID.randomUUID())));

    assertThatThrownBy(() -> useCases.discover(projectId,
        new RuntimeTargetDiscoveryCommand(ServiceConnectionType.SSH, sshId, ServiceRuntimeProvider.DOCKER)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void invalidInputFailsClosed() {
    assertThatThrownBy(() -> useCases.discover(projectId, null))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> useCases.discover(projectId,
        new RuntimeTargetDiscoveryCommand(ServiceConnectionType.LOCAL, UUID.randomUUID(), ServiceRuntimeProvider.DOCKER)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> useCases.discover(projectId,
        new RuntimeTargetDiscoveryCommand(ServiceConnectionType.SSH, null, ServiceRuntimeProvider.DOCKER)))
        .isInstanceOf(ValidationException.class);
  }

  private RuntimeTargetCandidate candidate(String id, ServiceRuntimeProvider provider) {
    return new RuntimeTargetCandidate(id, id, provider, RuntimeTargetStatus.AVAILABLE, null, null, null);
  }

  private SshConnection ssh(UUID id, UUID owner) {
    return new SshConnection(id, owner, "sandbox", "host", 22, "op", "/key", Instant.EPOCH, Instant.EPOCH);
  }
}
