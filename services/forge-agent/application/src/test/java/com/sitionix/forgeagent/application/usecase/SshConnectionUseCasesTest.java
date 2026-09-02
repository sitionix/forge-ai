package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.exception.NotFoundException;
import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.Project;
import com.sitionix.forgeagent.domain.model.SshConnection;
import com.sitionix.forgeagent.domain.model.SshAuthType;
import com.sitionix.forgeagent.domain.port.ProjectRepository;
import com.sitionix.forgeagent.domain.port.AssetInspectionPort;
import com.sitionix.forgeagent.domain.port.SshConnectionRepository;
import com.sitionix.forgeagent.domain.port.SshConnectionProbePort;
import com.sitionix.forgeagent.domain.port.ServiceMetricsPort;
import com.sitionix.forgeagent.domain.port.ServiceProcessMetricsPort;
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
  private final SshConnectionProbePort probe = mock(SshConnectionProbePort.class);
  private final AssetInspectionPort inspection = mock(AssetInspectionPort.class);
  private final ServiceMetricsPort serviceMetrics = mock(ServiceMetricsPort.class);
  private final ServiceProcessMetricsPort serviceProcesses = mock(ServiceProcessMetricsPort.class);
  private SshConnectionUseCases useCases;

  @BeforeEach
  void setUp() {
    when(projects.findById(projectId))
        .thenReturn(
            Optional.of(new Project(projectId, "Project", "project", Instant.EPOCH, Instant.EPOCH)));
    when(connections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    useCases =
        new SshConnectionUseCases(
            projects, connections, probe, inspection, serviceMetrics, serviceProcesses,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
  }

  @Test
  void metricsInspectThePersistedConnectionWithoutAnAsset() {
    var connection = new SshConnection(UUID.randomUUID(), projectId, "Jessie", "host", 22,
        "ops", SshAuthType.PRIVATE_KEY, "/key", null, Instant.EPOCH, Instant.EPOCH);
    var metrics = new com.sitionix.forgeagent.domain.model.AssetMetrics(12.0, List.of(12.0),
        10L, 5L, null, null, null, List.of(), List.of(), null, List.of());
    when(connections.findById(connection.id())).thenReturn(Optional.of(connection));
    when(inspection.metrics(connection)).thenReturn(metrics);

    assertThat(useCases.metrics(projectId, connection.id())).isSameAs(metrics);
    verify(inspection).metrics(connection);
  }

  @Test
  void metricsRejectAConnectionOwnedByAnotherProject() {
    var connection = new SshConnection(UUID.randomUUID(), UUID.randomUUID(), "Other", "host", 22,
        "ops", SshAuthType.PRIVATE_KEY, "/key", null, Instant.EPOCH, Instant.EPOCH);
    when(connections.findById(connection.id())).thenReturn(Optional.of(connection));

    assertThatThrownBy(() -> useCases.metrics(projectId, connection.id()))
        .isInstanceOf(NotFoundException.class);
    verifyNoInteractions(inspection);
  }

  @Test
  void serviceMetricsUseTheOwnedPersistedConnection() {
    var connection = new SshConnection(UUID.randomUUID(), projectId, "Jessie", "host", 22,
        "ops", SshAuthType.PRIVATE_KEY, "/key", null, Instant.EPOCH, Instant.EPOCH);
    var snapshot = new com.sitionix.forgeagent.domain.model.ServiceMetricsSnapshot(
        Instant.EPOCH, List.of());
    when(connections.findById(connection.id())).thenReturn(Optional.of(connection));
    when(serviceMetrics.collect(connection)).thenReturn(snapshot);

    assertThat(useCases.serviceMetrics(projectId, connection.id())).isSameAs(snapshot);
    verify(serviceMetrics).collect(connection);
  }

  @Test
  void serviceMetricsRejectCrossProjectConnectionWithoutCollection() {
    var connection = new SshConnection(UUID.randomUUID(), UUID.randomUUID(), "Other", "host", 22,
        "ops", SshAuthType.PRIVATE_KEY, "/key", null, Instant.EPOCH, Instant.EPOCH);
    when(connections.findById(connection.id())).thenReturn(Optional.of(connection));

    assertThatThrownBy(() -> useCases.serviceMetrics(projectId, connection.id()))
        .isInstanceOf(NotFoundException.class);
    verifyNoInteractions(serviceMetrics);
  }

  @Test
  void serviceProcessesUseTheOwnedPersistedConnection() {
    var connection = new SshConnection(UUID.randomUUID(), projectId, "Jessie", "host", 22,
        "ops", SshAuthType.PRIVATE_KEY, "/key", null, Instant.EPOCH, Instant.EPOCH);
    var snapshot = new com.sitionix.forgeagent.domain.model.ServiceProcessMetricsSnapshot(
        "alpha.service", com.sitionix.forgeagent.domain.model.ProcessMetricsSort.CPU,
        Instant.EPOCH, List.of());
    when(connections.findById(connection.id())).thenReturn(Optional.of(connection));
    when(serviceProcesses.collect(connection, "alpha.service",
        com.sitionix.forgeagent.domain.model.ProcessMetricsSort.CPU)).thenReturn(snapshot);

    assertThat(useCases.serviceProcesses(projectId, connection.id(), "alpha.service",
        com.sitionix.forgeagent.domain.model.ProcessMetricsSort.CPU)).isSameAs(snapshot);
    verify(serviceProcesses).collect(connection, "alpha.service",
        com.sitionix.forgeagent.domain.model.ProcessMetricsSort.CPU);
  }

  @Test
  void serviceProcessesRejectCrossProjectConnectionWithoutCollection() {
    var connection = new SshConnection(UUID.randomUUID(), UUID.randomUUID(), "Other", "host", 22,
        "ops", SshAuthType.PRIVATE_KEY, "/key", null, Instant.EPOCH, Instant.EPOCH);
    when(connections.findById(connection.id())).thenReturn(Optional.of(connection));

    assertThatThrownBy(() -> useCases.serviceProcesses(projectId, connection.id(), "alpha.service",
        com.sitionix.forgeagent.domain.model.ProcessMetricsSort.CPU))
        .isInstanceOf(NotFoundException.class);
    verifyNoInteractions(serviceProcesses);
  }

  @Test
  void serviceProcessesRejectShellSyntaxBeforeCollection() {
    var connection = new SshConnection(UUID.randomUUID(), projectId, "Jessie", "host", 22,
        "ops", SshAuthType.PRIVATE_KEY, "/key", null, Instant.EPOCH, Instant.EPOCH);
    when(connections.findById(connection.id())).thenReturn(Optional.of(connection));

    assertThatThrownBy(() -> useCases.serviceProcesses(projectId, connection.id(),
        "alpha.service; touch /tmp/pwned",
        com.sitionix.forgeagent.domain.model.ProcessMetricsSort.CPU))
        .isInstanceOf(ValidationException.class);
    verifyNoInteractions(serviceProcesses);
  }

  @Test
  void serviceProcessesAcceptCanonicalEscapesAndEnforceSystemdLengthLimit() {
    var connection = new SshConnection(UUID.randomUUID(), projectId, "Jessie", "host", 22,
        "ops", SshAuthType.PRIVATE_KEY, "/key", null, Instant.EPOCH, Instant.EPOCH);
    when(connections.findById(connection.id())).thenReturn(Optional.of(connection));
    var escaped = "worker\\x2dblue@1.service";
    var snapshot = new com.sitionix.forgeagent.domain.model.ServiceProcessMetricsSnapshot(
        escaped, com.sitionix.forgeagent.domain.model.ProcessMetricsSort.CPU, Instant.EPOCH, List.of());
    when(serviceProcesses.collect(connection, escaped,
        com.sitionix.forgeagent.domain.model.ProcessMetricsSort.CPU)).thenReturn(snapshot);

    assertThat(useCases.serviceProcesses(projectId, connection.id(), escaped,
        com.sitionix.forgeagent.domain.model.ProcessMetricsSort.CPU)).isSameAs(snapshot);
    var length255 = "a".repeat(247) + ".service";
    when(serviceProcesses.collect(connection, length255,
        com.sitionix.forgeagent.domain.model.ProcessMetricsSort.CPU)).thenReturn(snapshot);
    useCases.serviceProcesses(projectId, connection.id(), length255,
        com.sitionix.forgeagent.domain.model.ProcessMetricsSort.CPU);
    assertThatThrownBy(() -> useCases.serviceProcesses(projectId, connection.id(),
        "a".repeat(248) + ".service", com.sitionix.forgeagent.domain.model.ProcessMetricsSort.CPU))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> useCases.serviceProcesses(projectId, connection.id(),
        "bad@@unit.service", com.sitionix.forgeagent.domain.model.ProcessMetricsSort.CPU))
        .isInstanceOf(ValidationException.class);
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
  void runtimeConnectionStringRedactsAuthenticationMaterial() {
    var passwordConnection = new SshConnection(
        UUID.randomUUID(), projectId, "profile", "host", 22, "user", SshAuthType.PASSWORD,
        null, "p@ss;secret", Instant.EPOCH, Instant.EPOCH);
    var keyConnection = new SshConnection(
        UUID.randomUUID(), projectId, "profile", "host", 22, "user", "/keys/id",
        Instant.EPOCH, Instant.EPOCH);

    assertThat(passwordConnection.toString())
        .doesNotContain("p@ss;secret")
        .contains("password=<redacted>", "privateKeyPath=<redacted>");
    assertThat(keyConnection.toString()).doesNotContain("/keys/id");
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

  @Test
  void testValidatesAndProbesWithoutPersisting() {
    var command = new SaveSshConnectionCommand(
        "ancestor", "192.168.0.108", 22, "ancestor", SshAuthType.PASSWORD, null, "secret");

    useCases.test(projectId, command);

    verify(probe).test(argThat(connection -> connection.projectId().equals(projectId)
        && connection.password().equals("secret")));
    verify(connections, never()).save(any());
  }

  @Test
  void testRejectsMissingProjectAndInvalidFieldsBeforeProbing() {
    var command = new SaveSshConnectionCommand("profile", "host", 22, "user", "/key");
    assertThatThrownBy(() -> useCases.test(UUID.randomUUID(), command))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> useCases.test(projectId,
        new SaveSshConnectionCommand("profile", "bad;host", 22, "user", "/key")))
        .isInstanceOf(ValidationException.class);
    verifyNoInteractions(probe);
  }
}
