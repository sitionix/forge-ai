package com.sitionix.forgeagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.api.dto.RuntimeTargetDiscoveryRequest;
import com.sitionix.forgeagent.application.usecase.RuntimeTargetDiscoveryCommand;
import com.sitionix.forgeagent.application.usecase.RuntimeTargetDiscoveryUseCases;
import com.sitionix.forgeagent.domain.model.RuntimeTargetCandidate;
import com.sitionix.forgeagent.domain.model.RuntimeTargetStatus;
import com.sitionix.forgeagent.domain.model.ServiceConnectionType;
import com.sitionix.forgeagent.domain.model.ServiceRuntimeProvider;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeTargetDiscoveryControllerTest {
  @Test
  void delegatesTypedDiscoveryRequestToUseCase() {
    var useCases = mock(RuntimeTargetDiscoveryUseCases.class);
    var controller =
        new RuntimeTargetDiscoveryController(
            useCases, new ForgeAgentApiMapper(new ObjectMapper()));
    UUID projectId = UUID.randomUUID();
    UUID sshId = UUID.randomUUID();
    var command =
        new RuntimeTargetDiscoveryCommand(
            ServiceConnectionType.SSH, sshId, ServiceRuntimeProvider.SYSTEMD);
    var candidate =
        new RuntimeTargetCandidate(
            "forge-agent.service",
            "forge-agent.service",
            ServiceRuntimeProvider.SYSTEMD,
            RuntimeTargetStatus.AVAILABLE,
            null,
            null,
            null);
    when(useCases.discover(projectId, command)).thenReturn(List.of(candidate));

    var response =
        controller.discover(
            projectId,
            new RuntimeTargetDiscoveryRequest(
                ServiceConnectionType.SSH, sshId, ServiceRuntimeProvider.SYSTEMD));

    assertThat(response).hasSize(1);
    assertThat(response.getFirst().id()).isEqualTo("forge-agent.service");
    assertThat(response.getFirst().provider()).isEqualTo(ServiceRuntimeProvider.SYSTEMD);
    verify(useCases).discover(projectId, command);
  }
}
