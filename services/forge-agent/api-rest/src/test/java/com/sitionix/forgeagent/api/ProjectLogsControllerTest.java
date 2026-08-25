package com.sitionix.forgeagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.api.dto.LogDiscoveryRequest;
import com.sitionix.forgeagent.api.dto.LogSourceRequest;
import com.sitionix.forgeagent.api.dto.SshConnectionRequest;
import com.sitionix.forgeagent.application.usecase.LogSourceUseCases;
import com.sitionix.forgeagent.application.usecase.SshConnectionUseCases;
import com.sitionix.forgeagent.application.usecase.SaveLogSourceCommand;
import com.sitionix.forgeagent.application.usecase.SaveSshConnectionCommand;
import com.sitionix.forgeagent.domain.model.DockerLogConfiguration;
import com.sitionix.forgeagent.domain.model.LogConnectionType;
import com.sitionix.forgeagent.domain.model.LogProviderType;
import com.sitionix.forgeagent.domain.model.LogSource;
import com.sitionix.forgeagent.domain.model.SshConnection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ProjectLogsControllerTest {
  private LogSourceUseCases logs;
  private SshConnectionUseCases ssh;
  private ProjectLogSseService streaming;
  private ProjectLogsController controller;

  @BeforeEach
  void setUp() {
    this.logs = mock(LogSourceUseCases.class);
    this.ssh = mock(SshConnectionUseCases.class);
    this.streaming = mock(ProjectLogSseService.class);
    this.controller = new ProjectLogsController(this.logs, this.ssh, this.streaming);
  }

  @Test
  void delegatesCrudValidationAndDiscoveryUsingTypedConfiguration() {
    final UUID projectId = UUID.randomUUID();
    final UUID sourceId = UUID.randomUUID();
    final LogSourceRequest request =
        new LogSourceRequest(
            "mission",
            null,
            LogConnectionType.LOCAL,
            null,
            LogProviderType.DOCKER,
            "mission",
            null,
            null,
            null,
            null,
            true);
    final SaveLogSourceCommand command =
        new SaveLogSourceCommand(
            "mission",
            null,
            LogConnectionType.LOCAL,
            null,
            LogProviderType.DOCKER,
            new DockerLogConfiguration("mission", null, null),
            true);
    final LogSource source = source(projectId, sourceId);
    when(this.logs.list(projectId)).thenReturn(List.of(source));
    when(this.logs.create(projectId, command)).thenReturn(source);
    when(this.logs.update(projectId, sourceId, command)).thenReturn(source);

    assertThat(this.controller.list(projectId)).hasSize(1);
    assertThat(this.controller.create(projectId, request).getBody().id()).isEqualTo(sourceId);
    assertThat(this.controller.update(projectId, sourceId, request).id()).isEqualTo(sourceId);
    this.controller.validate(projectId, request);
    this.controller.delete(projectId, sourceId);

    verify(this.logs).validateTarget(projectId, command);
    verify(this.logs).delete(projectId, sourceId);

    final LogDiscoveryRequest discovery =
        new LogDiscoveryRequest(LogConnectionType.LOCAL, null, LogProviderType.DOCKER, null);
    assertThat(this.controller.discover(projectId, discovery)).isEmpty();
    verify(this.logs)
        .discover(projectId, LogConnectionType.LOCAL, null, LogProviderType.DOCKER, null);
  }

  @Test
  void delegatesReusableSshProfilesAndStreaming() {
    final UUID projectId = UUID.randomUUID();
    final UUID sourceId = UUID.randomUUID();
    final UUID sshId = UUID.randomUUID();
    final SshConnection connection =
        new SshConnection(
            sshId,
            projectId,
            "rover",
            "rover.local",
            22,
            "operator",
            "/keys/id",
            Instant.EPOCH,
            Instant.EPOCH);
    final SshConnectionRequest request =
        new SshConnectionRequest("rover", "rover.local", 22, "operator", "/keys/id");
    when(this.ssh.list(projectId)).thenReturn(List.of(connection));
    when(this.ssh.create(
            projectId,
            new SaveSshConnectionCommand(
                "rover", "rover.local", 22, "operator", "/keys/id")))
        .thenReturn(connection);
    final SseEmitter emitter = new SseEmitter();
    when(this.streaming.stream(projectId, List.of(sourceId), 100)).thenReturn(emitter);

    assertThat(this.controller.sshList(projectId)).hasSize(1);
    assertThat(this.controller.sshCreate(projectId, request).id()).isEqualTo(sshId);
    assertThat(this.controller.stream(projectId, List.of(sourceId), 100)).isSameAs(emitter);
  }

  private static LogSource source(final UUID projectId, final UUID sourceId) {
    return new LogSource(
        sourceId,
        projectId,
        "mission",
        null,
        LogConnectionType.LOCAL,
        null,
        LogProviderType.DOCKER,
        new DockerLogConfiguration("mission", null, null),
        true,
        Instant.EPOCH,
        Instant.EPOCH);
  }
}
