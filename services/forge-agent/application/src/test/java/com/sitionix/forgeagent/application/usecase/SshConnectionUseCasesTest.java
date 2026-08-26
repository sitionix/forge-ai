package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.model.SshConnection;
import com.sitionix.forgeagent.domain.model.SshAuthType;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.SshConnectionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SshConnectionUseCasesTest {
  private final UUID projectId = UUID.randomUUID();
  private final ProjectRepository projects = mock(ProjectRepository.class);
  private final SshConnectionRepository connections = mock(SshConnectionRepository.class);
  private SshConnectionUseCases useCases;

  @BeforeEach
  void setUp() {
    when(projects.findById(projectId))
        .thenReturn(
            Optional.of(new Project(projectId, "Project", "project", Instant.EPOCH, Instant.EPOCH)));
    when(connections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    useCases =
        new SshConnectionUseCases(
            projects, connections, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
  }

  @Test
  void creationReturnsAReadSafeProfileWithoutThePrivateKeyPath() {
    SshConnection created =
        useCases.create(
            projectId,
            new SaveSshConnectionCommand("rover", "rover.local", 22, "operator", "/keys/id"));

    assertThat(created.privateKeyPath()).isNull();
    assertThat(created.projectId()).isEqualTo(projectId);
  }

  @Test
  void listRedactsPrivateKeyPathsForEveryReusableProfile() {
    when(connections.findByProjectId(projectId))
        .thenReturn(
            List.of(
                new SshConnection(
                    UUID.randomUUID(),
                    projectId,
                    "rover",
                    "rover.local",
                    22,
                    "operator",
                    "/keys/id",
                    Instant.EPOCH,
                    Instant.EPOCH)));

    assertThat(useCases.list(projectId)).allMatch(profile -> profile.privateKeyPath() == null);
  }

  @Test
  void passwordCreationReturnsAuthTypeWithoutAuthenticationMaterial() {
    SshConnection created =
        useCases.create(
            projectId,
            new SaveSshConnectionCommand(
                "ancestor", "192.168.0.108", 22, "ancestor", SshAuthType.PASSWORD, null,
                "secret;$(data)"));

    assertThat(created.authType()).isEqualTo(SshAuthType.PASSWORD);
    assertThat(created.password()).isNull();
    assertThat(created.privateKeyPath()).isNull();
  }

  @Test
  void rejectsMismatchedAuthenticationMaterial() {
    assertThatThrownBy(
            () ->
                useCases.create(
                    projectId,
                    new SaveSshConnectionCommand(
                        "bad", "host", 22, "operator", SshAuthType.PASSWORD, "/key", "secret")))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                useCases.create(
                    projectId,
                    new SaveSshConnectionCommand(
                        "bad", "host", 22, "operator", SshAuthType.PRIVATE_KEY, "/key", "secret")))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void rejectsHostAndUsernameShellInjectionAtProfileCreation() {
    for (String injected : List.of("host;touch", "$(id)", "host&&id", "host|id", "`id`")) {
      assertThatThrownBy(
              () ->
                  useCases.create(
                      projectId,
                      new SaveSshConnectionCommand(
                          "bad", injected, 22, "operator", "/keys/id")))
          .isInstanceOf(ValidationException.class);
    }
  }
}
