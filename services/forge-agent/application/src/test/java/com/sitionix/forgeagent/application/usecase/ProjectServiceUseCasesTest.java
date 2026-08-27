package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sitionix.forgeagent.domain.exception.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectServiceUseCasesTest {
  private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");
  private final ProjectRepository projects = mock(ProjectRepository.class);
  private final ProjectServiceRepository services = mock(ProjectServiceRepository.class);
  private final ProjectRepositoryLinkRepository repositories =
      mock(ProjectRepositoryLinkRepository.class);
  private final SshConnectionRepository connections = mock(SshConnectionRepository.class);
  private final ServiceRuntimeInspectionPort runtime = mock(ServiceRuntimeInspectionPort.class);
  private final UUID projectId = UUID.randomUUID();
  private final ProjectServiceUseCases useCases =
      new ProjectServiceUseCases(
          projects, services, repositories, connections, runtime,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void setUp() {
    when(projects.findById(projectId))
        .thenReturn(Optional.of(new Project(projectId, "Project", "project", NOW, NOW)));
    when(services.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void createsListsUpdatesGetsAndDeletesOwnedServices() {
    final SaveProjectServiceCommand create = command("api", null, localDocker());
    final ProjectService created = useCases.create(projectId, create);
    when(services.findByProjectId(projectId)).thenReturn(List.of(created));
    when(services.findById(created.id())).thenReturn(Optional.of(created));

    assertThat(useCases.list(projectId)).containsExactly(created);
    assertThat(useCases.get(projectId, created.id())).isEqualTo(created);
    final ProjectService updated =
        useCases.update(projectId, created.id(), command("web", null, localDocker()));
    assertThat(updated.name()).isEqualTo("web");
    assertThat(updated.createdAt()).isEqualTo(NOW);
    useCases.delete(projectId, created.id());
    verify(services).delete(created);
  }

  @Test
  void serviceIdsAreProjectIsolated() {
    final UUID otherProject = UUID.randomUUID();
    final ProjectService other =
        new ProjectService(UUID.randomUUID(), otherProject, "api", null, localDocker(), NOW, NOW);
    when(services.findById(other.id())).thenReturn(Optional.of(other));

    assertThatThrownBy(() -> useCases.get(projectId, other.id()))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Service not found");
  }

  @Test
  void repositoryMustBelongToProject() {
    final UUID repositoryId = UUID.randomUUID();
    when(repositories.findById(repositoryId))
        .thenReturn(
            Optional.of(
                new ProjectRepositoryLink(repositoryId, UUID.randomUUID(), "git@example/repo", NOW)));

    assertThatThrownBy(
            () -> useCases.create(projectId, command("api", repositoryId, localDocker())))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Project repository not found");
  }

  @Test
  void ownedRepositoryCanBeAssociated() {
    final UUID repositoryId = UUID.randomUUID();
    when(repositories.findById(repositoryId))
        .thenReturn(
            Optional.of(new ProjectRepositoryLink(repositoryId, projectId, "git@example/repo", NOW)));

    assertThat(useCases.create(projectId, command("api", repositoryId, localDocker())).repositoryId())
        .isEqualTo(repositoryId);
  }

  @Test
  void sshProfileMustExistInTheSameProject() {
    final UUID sshId = UUID.randomUUID();
    when(connections.findById(sshId)).thenReturn(Optional.of(ssh(sshId, UUID.randomUUID())));

    assertThatThrownBy(
            () -> useCases.create(projectId, command("api", null, sshDocker(sshId))))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("SSH connection not found");
  }

  @Test
  void localCannotCarrySshAndSshRequiresAProfile() {
    assertThatThrownBy(
            () ->
                useCases.create(
                    projectId,
                    command(
                        "api", null,
                        new ServiceRuntimeTarget(
                            ServiceConnectionType.LOCAL, UUID.randomUUID(),
                            ServiceRuntimeProvider.DOCKER, "api", null))))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Local runtime cannot reference SSH");
    assertThatThrownBy(
            () ->
                useCases.create(
                    projectId,
                    command(
                        "api", null,
                        new ServiceRuntimeTarget(
                            ServiceConnectionType.SSH, null,
                            ServiceRuntimeProvider.DOCKER, "api", null))))
        .isInstanceOf(ValidationException.class)
        .hasMessage("SSH connection is required");
  }

  @Test
  void runtimeUsesTheOwnedTypedSshProfile() {
    final UUID sshId = UUID.randomUUID();
    final SshConnection ssh = ssh(sshId, projectId);
    final ProjectService service =
        new ProjectService(UUID.randomUUID(), projectId, "api", null, sshDocker(sshId), NOW, NOW);
    final ServiceRuntimeView expected =
        new ServiceRuntimeView(
            ServiceRuntimeStatus.RUNNING, ServiceRuntimeProvider.DOCKER,
            ServiceConnectionType.SSH, "api", NOW, Duration.ZERO, Map.of(), null);
    when(services.findById(service.id())).thenReturn(Optional.of(service));
    when(connections.findById(sshId)).thenReturn(Optional.of(ssh));
    when(runtime.inspect(service, ssh)).thenReturn(expected);

    assertThat(useCases.runtime(projectId, service.id())).isEqualTo(expected);
  }

  private SaveProjectServiceCommand command(
      final String name, final UUID repositoryId, final ServiceRuntimeTarget target) {
    return new SaveProjectServiceCommand(name, repositoryId, target);
  }

  private ServiceRuntimeTarget localDocker() {
    return new ServiceRuntimeTarget(
        ServiceConnectionType.LOCAL, null, ServiceRuntimeProvider.DOCKER, "api", null);
  }

  private ServiceRuntimeTarget sshDocker(final UUID sshId) {
    return new ServiceRuntimeTarget(
        ServiceConnectionType.SSH, sshId, ServiceRuntimeProvider.DOCKER, "api", null);
  }

  private SshConnection ssh(final UUID id, final UUID owner) {
    return new SshConnection(
        id, owner, "host", "host.local", 22, "operator", "/keys/id", NOW, NOW);
  }
}
