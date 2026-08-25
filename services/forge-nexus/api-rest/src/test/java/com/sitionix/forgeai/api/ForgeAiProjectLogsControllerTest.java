package com.sitionix.forgeai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sitionix.forgeai.api.agentproxy.AgentLogSourceRequest;
import com.sitionix.forgeai.api.agentproxy.AgentLogSourceResponse;
import com.sitionix.forgeai.api.agentproxy.AgentProxyApiMapper;
import com.sitionix.forgeai.domain.model.agentproxy.*;
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
            source.configuration(),
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
    doAnswer(
        invocation -> {
          invocation.<java.io.OutputStream>getArgument(3).write("event: log\n\n".getBytes());
          return null;
        })
        .when(logs)
        .stream(eq(projectId), eq(List.of(sourceId)), eq(100), any());

    var response = controller.stream(projectId, List.of(sourceId), 100);
    response.getBody().writeTo(output);

    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
    assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
    assertThat(output.toString()).isEqualTo("event: log\n\n");
    verify(logs).stream(projectId, List.of(sourceId), 100, output);
  }
}
