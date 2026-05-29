package com.sitionix.forgeai.it;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.application.job.ReadyToStartLaneJob;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.util.UUID;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge-ai.jobs.ready-to-start.fixed-delay-ms=100"
})
class ArchitectLaneDependencyResolutionIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @MockBean
    private CodexClient codexClient;

    @Autowired
    private ReadyToStartLaneJob readyToStartLaneJob;

    @Test
    @DisplayName("Should execute architect lane when dependency points to analyzer and input ticket is architect")
    void givenArchitectReadyLaneWithAnalyzerDependency_whenJobRuns_thenArchitectExecutionStarts() {
        //given
        final UUID ticketId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        final UUID analyzerLaneId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeAnalyzerLaneSeedTicket.json");

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeAnalyzerLane())
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", analyzerLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(analyzerLaneId.toString()))
                .assertDefault();

        this.awaitReadyProducedLanes();

        this.readyToStartLaneJob.run();

        //then
        verify(this.codexClient, atLeastOnce())
                .submit(any(AgentExecutionInput.class), eq("/dev/ttys999"));
    }

    private void awaitReadyProducedLanes() {
        final long timeoutAt = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < timeoutAt) {
            if (this.readyProducedLanesPresent()) {
                return;
            }
            this.sleep(100L);
        }
        throw new AssertionError("Produced lanes were not moved to READY_TO_START within timeout.");
    }

    private boolean readyProducedLanesPresent() {
        final TicketDocument ticket = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();
        final boolean readyArchitect = ticket.getLanes().stream()
                .anyMatch(lane -> lane.getType() == Agent.ARCHITECT && lane.getStatus() == LaneStatus.READY_TO_START);
        final boolean readyQaLead = ticket.getLanes().stream()
                .anyMatch(lane -> lane.getType() == Agent.QA_LEAD && lane.getStatus() == LaneStatus.READY_TO_START);
        return readyArchitect && readyQaLead;
    }

    private void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for READY_TO_START lanes.", e);
        }
    }
}
