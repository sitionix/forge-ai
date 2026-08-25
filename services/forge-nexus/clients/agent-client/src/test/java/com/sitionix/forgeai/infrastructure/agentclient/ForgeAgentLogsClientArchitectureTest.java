package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sitionix.forgeai.domain.model.agentproxy.*;
import com.sitionix.forgeai.domain.port.AgentLogStream;
import com.sitionix.forgeai.infrastructure.agentclient.dto.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

class ForgeAgentLogsClientArchitectureTest {
  private ForgeAgentHttpClient http = mock(ForgeAgentHttpClient.class);
  private ForgeAgentClientMapper mapper = mock(ForgeAgentClientMapper.class);
  private ForgeAgentClientCallExecutor executor = mock(ForgeAgentClientCallExecutor.class);
  private ForgeAgentLogStreamingHttpClient streaming = mock(ForgeAgentLogStreamingHttpClient.class);
  private ForgeAgentClientAdapter adapter;

  @BeforeEach
  void setup() {
    adapter = new ForgeAgentClientAdapter(http, mapper, executor, streaming);
  }

  @Test
  void crudUsesTypedRequestAndResponseThroughHttpClient() {
    UUID p = UUID.randomUUID();
    var command =
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
    var request =
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
    var response =
        new AgentLogSourceResponse(
            UUID.randomUUID(),
            p,
            "app",
            null,
            AgentLogConnectionType.LOCAL,
            null,
            AgentLogProviderType.DOCKER,
            new AgentLogConfigurationResponse("app", null, null, null, null),
            true,
            Instant.EPOCH,
            Instant.EPOCH);
    var domain =
        new AgentLogSource(
            response.id(),
            p,
            "app",
            null,
            AgentLogConnectionType.LOCAL,
            null,
            AgentLogProviderType.DOCKER,
            new AgentDockerLogConfiguration("app", null, null),
            true,
            Instant.EPOCH,
            Instant.EPOCH);
    when(mapper.toRequest(command)).thenReturn(request);
    when(executor.execute(any())).thenReturn(response);
    when(mapper.toDomain(response)).thenReturn(domain);
    assertThat(adapter.createProjectLogSource(p, command)).isEqualTo(domain);
    verify(executor).execute(any());
  }

  @Test
  void sseTransferStaysBehindAgentClientBoundary() {
    UUID p = UUID.randomUUID(), s = UUID.randomUUID();
    AgentLogStream expected = mock(AgentLogStream.class);
    when(executor.execute(any())).thenReturn(expected);

    assertThat(adapter.openProjectLogsStream(p, List.of(s), 100)).isSameAs(expected);

    verify(executor).execute(any());
    verifyNoInteractions(streaming);
  }

  @Test
  void discoveryIsTypedAndCrudNeverUsesRawPayloads() {
    UUID p = UUID.randomUUID(), sourceId = UUID.randomUUID();
    var command =
        new AgentLogDiscoveryCommand(
            AgentLogConnectionType.LOCAL, null, AgentLogProviderType.DOCKER, UUID.randomUUID());
    var request =
        new AgentLogDiscoveryRequest(
            command.connection(), null, command.provider(), command.repositoryId());
    var response =
        new AgentLogTargetCandidateResponse(
            "web",
            "web",
            AgentLogTargetStatus.AVAILABLE,
            "web:latest",
            "demo",
            "web",
            "/repo/compose.yaml",
            false);
    var target =
        new AgentLogTargetCandidate(
            "web",
            "web",
            AgentLogTargetStatus.AVAILABLE,
            "web:latest",
            "demo",
            "web",
            "/repo/compose.yaml",
            false);
    when(mapper.toRequest(command)).thenReturn(request);
    when(executor.execute(any())).thenReturn(List.of(response)).thenReturn(null);
    when(mapper.toDomain(response)).thenReturn(target);
    assertThat(adapter.discoverProjectLogTargets(p, command)).containsExactly(target);
    adapter.deleteProjectLogSource(p, sourceId);
    verify(executor, times(2)).execute(any());
    verifyNoInteractions(http);
  }
}
