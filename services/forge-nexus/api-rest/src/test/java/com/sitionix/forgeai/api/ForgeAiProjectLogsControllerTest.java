package com.sitionix.forgeai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.sitionix.forgeai.api.agentproxy.AgentLogConfigurationResponse;
import com.sitionix.forgeai.api.agentproxy.AgentLogDiscoveryRequest;
import com.sitionix.forgeai.api.agentproxy.AgentLogSourceRequest;
import com.sitionix.forgeai.api.agentproxy.AgentLogSourceResponse;
import com.sitionix.forgeai.api.agentproxy.AgentLogTargetCandidateResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProxyApiMapper;
import com.sitionix.forgeai.api.agentproxy.AgentSshConnectionRequest;
import com.sitionix.forgeai.api.agentproxy.AgentSshConnectionResponse;
import com.sitionix.forgeai.domain.model.agentproxy.*;
import com.sitionix.forgeai.domain.port.AgentLogStream;
import com.sitionix.forgeai.domain.usecase.ManageAgentProjectLogs;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ForgeAiProjectLogsControllerTest {

  @Test
  void createUsesTypedMapperAndUseCase() {
    ManageAgentProjectLogs logs = mock(ManageAgentProjectLogs.class);
    AgentProxyApiMapper mapper = mock(AgentProxyApiMapper.class);
    ForgeAiProjectLogsController controller = new ForgeAiProjectLogsController(logs, mapper);
    UUID projectId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    AgentLogSourceRequest request =
        new AgentLogSourceRequest(
            "app",
            null,
            AgentLogConnectionType.LOCAL,
            null,
            AgentLogProviderType.DOCKER,
            "app",
            null,
            null,
            null,
            null,
            true);
    SaveAgentLogSourceCommand command =
        new SaveAgentLogSourceCommand(
            "app",
            null,
            AgentLogConnectionType.LOCAL,
            null,
            AgentLogProviderType.DOCKER,
            "app",
            null,
            null,
            null,
            null,
            true);
    AgentLogSource source =
        new AgentLogSource(
            sourceId,
            projectId,
            "app",
            null,
            AgentLogConnectionType.LOCAL,
            null,
            AgentLogProviderType.DOCKER,
            new AgentDockerLogConfiguration("app", null, null),
            true,
            Instant.EPOCH,
            Instant.EPOCH);
    AgentLogSourceResponse response =
        new AgentLogSourceResponse(
            sourceId,
            projectId,
            "app",
            null,
            AgentLogConnectionType.LOCAL,
            null,
            AgentLogProviderType.DOCKER,
            new AgentLogConfigurationResponse("app", null, null, null, null),
            true,
            Instant.EPOCH,
            Instant.EPOCH);
    when(mapper.toCommand(request)).thenReturn(command);
    when(logs.create(projectId, command)).thenReturn(source);
    when(mapper.toResponse(source)).thenReturn(response);

    assertThat(controller.create(projectId, request).getBody()).isEqualTo(response);
    verify(logs).create(projectId, command);
  }

  @Test
  void sseResponseStreamsThroughUseCaseWithProxySafeHeaders() throws Exception {
    ManageAgentProjectLogs logs = mock(ManageAgentProjectLogs.class);
    ForgeAiProjectLogsController controller =
        new ForgeAiProjectLogsController(logs, mock(AgentProxyApiMapper.class));
    UUID projectId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    AgentLogStream stream = mock(AgentLogStream.class);
    byte[] event = "event: log\n\n".getBytes();
    when(stream.read(any(java.nio.ByteBuffer.class)))
        .thenAnswer(
            invocation -> {
              invocation.<java.nio.ByteBuffer>getArgument(0).put(event);
              return event.length;
            })
        .thenReturn(-1);
    when(logs.openStream(projectId, List.of(sourceId), 100)).thenReturn(stream);

    var response = controller.stream(projectId, List.of(sourceId), 100);
    response.getBody().writeTo(output);

    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
    assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
    assertThat(output.toString()).isEqualTo("event: log\n\n");
    verify(logs).openStream(projectId, List.of(sourceId), 100);
    verify(stream).close();
  }

  @Test
  void streamStartupFailureIsRaisedBeforeAnOkResponseExists() {
    ManageAgentProjectLogs logs = mock(ManageAgentProjectLogs.class);
    ForgeAiProjectLogsController controller =
        new ForgeAiProjectLogsController(logs, mock(AgentProxyApiMapper.class));
    UUID projectId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    when(logs.openStream(projectId, List.of(sourceId), 100))
        .thenThrow(new org.springframework.web.client.ResourceAccessException("offline"));

    assertThatThrownBy(() -> controller.stream(projectId, List.of(sourceId), 100))
        .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
  }

  @Test
  void delegatesListUpdateDeleteDiscoveryValidationAndSshOperations() {
    ManageAgentProjectLogs logs = mock(ManageAgentProjectLogs.class);
    AgentProxyApiMapper mapper = mock(AgentProxyApiMapper.class);
    ForgeAiProjectLogsController controller = new ForgeAiProjectLogsController(logs, mapper);
    UUID projectId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    UUID sshId = UUID.randomUUID();
    AgentLogSourceRequest sourceRequest =
        new AgentLogSourceRequest(
            "app",
            null,
            AgentLogConnectionType.LOCAL,
            null,
            AgentLogProviderType.DOCKER,
            "app",
            null,
            null,
            null,
            null,
            true);
    SaveAgentLogSourceCommand save =
        new SaveAgentLogSourceCommand(
            "app",
            null,
            AgentLogConnectionType.LOCAL,
            null,
            AgentLogProviderType.DOCKER,
            "app",
            null,
            null,
            null,
            null,
            true);
    AgentLogSource source =
        new AgentLogSource(
            sourceId,
            projectId,
            "app",
            null,
            AgentLogConnectionType.LOCAL,
            null,
            AgentLogProviderType.DOCKER,
            new AgentDockerLogConfiguration("app", null, null),
            true,
            Instant.EPOCH,
            Instant.EPOCH);
    AgentLogSourceResponse sourceResponse =
        new AgentLogSourceResponse(
            sourceId,
            projectId,
            "app",
            null,
            AgentLogConnectionType.LOCAL,
            null,
            AgentLogProviderType.DOCKER,
            new AgentLogConfigurationResponse("app", null, null, null, null),
            true,
            Instant.EPOCH,
            Instant.EPOCH);
    when(mapper.toCommand(sourceRequest)).thenReturn(save);
    when(logs.list(projectId)).thenReturn(List.of(source));
    when(logs.update(projectId, sourceId, save)).thenReturn(source);
    when(mapper.toResponse(source)).thenReturn(sourceResponse);

    assertThat(controller.list(projectId)).containsExactly(sourceResponse);
    assertThat(controller.update(projectId, sourceId, sourceRequest)).isEqualTo(sourceResponse);
    controller.validate(projectId, sourceRequest);
    controller.delete(projectId, sourceId);
    verify(logs).validate(projectId, save);
    verify(logs).delete(projectId, sourceId);

    AgentLogDiscoveryRequest discoveryRequest =
        new AgentLogDiscoveryRequest(
            AgentLogConnectionType.LOCAL, null, AgentLogProviderType.DOCKER, null);
    AgentLogDiscoveryCommand discovery =
        new AgentLogDiscoveryCommand(
            AgentLogConnectionType.LOCAL, null, AgentLogProviderType.DOCKER, null);
    AgentLogTargetCandidate candidate =
        new AgentLogTargetCandidate(
            "app", "app", AgentLogTargetStatus.RUNNING, "app:latest", null, null, null, false);
    AgentLogTargetCandidateResponse candidateResponse =
        new AgentLogTargetCandidateResponse(
            "app", "app", AgentLogTargetStatus.RUNNING, "app:latest", null, null, null, false);
    when(mapper.toCommand(discoveryRequest)).thenReturn(discovery);
    when(logs.discover(projectId, discovery)).thenReturn(List.of(candidate));
    when(mapper.toResponse(candidate)).thenReturn(candidateResponse);
    assertThat(controller.discover(projectId, discoveryRequest)).containsExactly(candidateResponse);

  }
}
