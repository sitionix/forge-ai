package com.sitionix.forgeai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.sitionix.forgeai.api.agentproxy.*;
import com.sitionix.forgeai.domain.model.agentproxy.*;
import com.sitionix.forgeai.domain.usecase.ManageAgentProjectSshConnections;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ForgeAiProjectSshConnectionsControllerTest {
  @Test void delegatesSshOperationsAndMetricsThroughTheDedicatedBoundary() {
    var ssh = mock(ManageAgentProjectSshConnections.class); var mapper = mock(AgentProxyApiMapper.class);
    var controller = new ForgeAiProjectSshConnectionsController(ssh, mapper);
    var projectId = UUID.randomUUID(); var connectionId = UUID.randomUUID();
    var request = new AgentSshConnectionRequest("rover", "rover.local", 22, "operator", "/keys/id");
    var command = new CreateAgentSshConnectionCommand("rover", "rover.local", 22, "operator", "/keys/id");
    var connection = new AgentSshConnection(connectionId, projectId, "rover", "rover.local", 22, "operator", Instant.EPOCH, Instant.EPOCH);
    var response = new AgentSshConnectionResponse(connectionId, projectId, "rover", "rover.local", 22, "operator", Instant.EPOCH, Instant.EPOCH);
    var metrics = new AgentAssetMetrics(10.0, List.of(10.0), 10L, 5L, null, null, null, List.of(), List.of(), null, List.of());
    var metricsResponse = new AgentAssetMetricsResponse(10.0, List.of(10.0), 10L, 5L, null, null, null, List.of(), List.of(), null, List.of());
    var serviceSnapshot = new com.sitionix.forgeai.domain.model.agentproxy.AgentServiceMetricsSnapshot(
        Instant.EPOCH, List.of(new com.sitionix.forgeai.domain.model.agentproxy.AgentServiceResourceMetrics(
            "alpha.service", "Alpha", 42L, 1024L, 3L)));
    var serviceResponse = new com.sitionix.forgeai.api.agentproxy.AgentServiceMetricsResponse(
        Instant.EPOCH, List.of(new com.sitionix.forgeai.api.agentproxy.AgentServiceResourceMetricsResponse(
            "alpha.service", "Alpha", 42L, 1024L, 3L)));
    when(mapper.toCommand(request)).thenReturn(command); when(ssh.list(projectId)).thenReturn(List.of(connection));
    when(ssh.create(projectId, command)).thenReturn(connection); when(mapper.toResponse(connection)).thenReturn(response);
    when(ssh.metrics(projectId, connectionId)).thenReturn(metrics); when(mapper.toResponse(metrics)).thenReturn(metricsResponse);
    when(ssh.serviceMetrics(projectId, connectionId)).thenReturn(serviceSnapshot);
    when(mapper.toResponse(serviceSnapshot)).thenReturn(serviceResponse);
    assertThat(controller.list(projectId)).containsExactly(response); assertThat(controller.create(projectId, request)).isEqualTo(response);
    controller.test(projectId, request); assertThat(controller.metrics(projectId, connectionId)).isSameAs(metricsResponse);
    assertThat(controller.serviceMetrics(projectId, connectionId)).isSameAs(serviceResponse);
    verify(ssh).test(projectId, command);
  }
}
