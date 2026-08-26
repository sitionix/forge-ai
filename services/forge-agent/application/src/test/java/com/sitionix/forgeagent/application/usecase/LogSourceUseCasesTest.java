package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sitionix.forgeagent.domain.exception.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class LogSourceUseCasesTest {
  @Mock ProjectRepository projects;
  @Mock LogSourceRepository sources;
  @Mock SshConnectionRepository connections;
  @Mock DockerLogPort docker;
  @Mock RemoteLogPort remote;
  @Mock ProjectRepositoryLinkRepository repositories;
  @Mock LocalProjectWorkspacePort workspaces;
  @Mock GitRepositoryPort git;
  UUID projectId = UUID.randomUUID();
  LogSourceUseCases useCases;

  @BeforeEach
  void setup() {
    useCases =
        new LogSourceUseCases(
            projects,
            sources,
            connections,
            docker,
            remote,
            repositories,
            workspaces,
            git,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    when(projects.findById(projectId))
        .thenReturn(Optional.of(new Project(projectId, "P", "p", Instant.EPOCH, Instant.EPOCH)));
    lenient().when(sources.save(any())).thenAnswer(i -> i.getArgument(0));
  }

  @Test
  void customSourceBelongsToProjectAndNeedsNoService() {
    var result = useCases.create(projectId, command("one", null));
    assertThat(result.projectId()).isEqualTo(projectId);
    assertThat(result.serviceId()).isNull();
  }

  @Test
  void multipleSourcesCanBeCreatedWithoutOnePerServiceConstraint() {
    useCases.create(projectId, command("one", null));
    useCases.create(projectId, command("two", null));
    verify(sources, times(2)).save(any());
  }

  @Test
  void crossProjectSourceIsHidden() {
    UUID other = UUID.randomUUID();
    var source =
        new LogSource(
            UUID.randomUUID(),
            other,
            "x",
            null,
            LogConnectionType.LOCAL,
            null,
            LogProviderType.DOCKER,
            new DockerLogConfiguration("c", null, null),
            true,
            Instant.EPOCH,
            Instant.EPOCH);
    when(sources.findById(source.id())).thenReturn(Optional.of(source));
    assertThatThrownBy(() -> useCases.delete(projectId, source.id()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void serviceAssociationFailsExplicitlyWhenServiceModelIsUnavailable() {
    assertThatThrownBy(() -> useCases.create(projectId, command("one", UUID.randomUUID())))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("no Service resource");
  }

  @Test
  void createRejectsInvalidProviderConfiguration() {
    assertThatThrownBy(
            () ->
                useCases.create(
                    projectId,
                    new SaveLogSourceCommand(
                        "bad",
                        null,
                        LogConnectionType.LOCAL,
                        null,
                        LogProviderType.DOCKER,
                        new DockerLogConfiguration(null, null, null),
                        true)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void localSystemdIsRejectedOnCreate() {
    assertThatThrownBy(
            () ->
                useCases.create(
                    projectId,
                    new SaveLogSourceCommand(
                        "bad",
                        null,
                        LogConnectionType.LOCAL,
                        null,
                        LogProviderType.SYSTEMD,
                        new SystemdLogConfiguration("docker.service"),
                        true)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void systemdUnitIsRequiredOnlyForUnitMode() {
    UUID sshId = UUID.randomUUID();
    when(connections.findById(sshId)).thenReturn(Optional.of(ssh(sshId)));

    assertThatThrownBy(() -> useCases.create(projectId,
        systemd(sshId, new SystemdLogConfiguration(SystemdTargetMode.UNIT, null))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("unit");

    var source = useCases.create(projectId,
        systemd(sshId, new SystemdLogConfiguration(SystemdTargetMode.FULL_JOURNAL, null)));
    assertThat(source.configuration()).isEqualTo(
        new SystemdLogConfiguration(SystemdTargetMode.FULL_JOURNAL, null));
  }

  @Test
  void sshConnectionFromAnotherProjectIsRejected() {
    UUID id = UUID.randomUUID();
    when(connections.findById(id))
        .thenReturn(
            Optional.of(
                new SshConnection(
                    id,
                    UUID.randomUUID(),
                    "x",
                    "host",
                    22,
                    "user",
                    "/key",
                    Instant.EPOCH,
                    Instant.EPOCH)));
    assertThatThrownBy(
            () ->
                useCases.create(
                    projectId,
                    new SaveLogSourceCommand(
                        "x",
                        null,
                        LogConnectionType.SSH,
                        id,
                        LogProviderType.FILE,
                        new FileLogConfiguration("/var/log/x"),
                        true)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void localComposeDiscoveryUsesOwnedClonedRepository() {
    UUID repositoryId = UUID.randomUUID();
    var path = Path.of("/workspace/repo");
    when(repositories.findById(repositoryId))
        .thenReturn(
            Optional.of(
                new ProjectRepositoryLink(
                    repositoryId, projectId, "git@example/repo.git", Instant.EPOCH)));
    when(git.resolveRepositoryName(anyString())).thenReturn("repo");
    when(workspaces.resolveRepositoryWorkspaceState(eq(projectId), any()))
        .thenReturn(new ProjectRepositoryWorkspaceState(repositoryId, path, true));
    var candidate =
        new LogTargetCandidate(
            "web",
            "web",
            LogTargetStatus.AVAILABLE,
            null,
            null,
            "web",
            path.resolve("compose.yaml").toString(),
            false);
    when(docker.discover(any())).thenReturn(List.of());
    when(docker.discoverComposeServices(path, null)).thenReturn(List.of(candidate));
    assertThat(
            useCases.discover(
                projectId, LogConnectionType.LOCAL, null, LogProviderType.DOCKER, repositoryId))
        .containsExactly(candidate);
  }

  @Test
  void sshDiscoveryRejectsLocalRepositoryContext() {
    assertThatThrownBy(
            () ->
                useCases.discover(
                    projectId,
                    LogConnectionType.SSH,
                    UUID.randomUUID(),
                    LogProviderType.DOCKER,
                    UUID.randomUUID()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("only locally");
  }

  private SaveLogSourceCommand command(String name, UUID service) {
    return new SaveLogSourceCommand(
        name,
        service,
        LogConnectionType.LOCAL,
        null,
        LogProviderType.DOCKER,
        new DockerLogConfiguration("container", null, null),
        true);
  }

  private SaveLogSourceCommand systemd(UUID sshId, SystemdLogConfiguration configuration) {
    return new SaveLogSourceCommand(
        "journal", null, LogConnectionType.SSH, sshId, LogProviderType.SYSTEMD,
        configuration, true);
  }

  private SshConnection ssh(UUID id) {
    return new SshConnection(
        id, projectId, "ssh", "host", 22, "user", "/key", Instant.EPOCH, Instant.EPOCH);
  }
}
