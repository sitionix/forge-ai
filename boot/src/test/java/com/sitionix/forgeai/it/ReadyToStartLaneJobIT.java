package com.sitionix.forgeai.it;

import com.sitionix.forgeai.application.job.ReadyToStartLaneJob;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.model.ticket.TicketStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.infrastructure.mongodb.entity.LaneDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=100")
class ReadyToStartLaneJobIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private ReadyToStartLaneJob readyToStartLaneJob;

    @Autowired
    private TicketRepository ticketRepository;

    @Test
    @DisplayName("Should move analyzer lanes to in progress by scheduler job")
    void givenStartForgeRequest_whenSchedulerRuns_thenMoveAnalyzerLanesToInProgress() {
        //when
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.startForge())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticket").value("SITIONIX-1"))
                .assertDefault();

        this.readyToStartLaneJob.run();

        //then
        this.awaitAnalyzerLanesStarted(Duration.ofSeconds(5));

        //then
        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(actual -> {
                    final List<LaneDocument> lanes = actual.getLanes();
                    return lanes.stream()
                            .filter(lane -> Objects.equals(Agent.ANALYZER, lane.getType()))
                            .allMatch(lane -> Objects.equals(LaneStatus.IN_PROGRESS, lane.getStatus())
                                    || Objects.equals(LaneStatus.COMPLETED, lane.getStatus()));
                });
        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(actual -> Objects.equals(TicketStatus.IN_PROGRESS, actual.getStatus()));
    }

    @Test
    @DisplayName("Should keep downstream ready lanes discoverable after ticket moves to in progress")
    void givenInProgressTicketWithReadyLane_whenFindingReadyLanes_thenReturnReadyLane() {
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        this.ticketRepository.save(Ticket.builder()
                .id(ticketId)
                .ticketKey("SITIONIX-READY")
                .taskDescription("task")
                .status(TicketStatus.IN_PROGRESS)
                .lanes(List.of(Lane.builder()
                        .id(laneId)
                        .agent(Agent.ARCHITECT)
                        .scope("automationservice-sox")
                        .serviceId("automationservice-sox")
                        .status(LaneStatus.READY_TO_START)
                        .attempt(0)
                        .inputTaskIds(Set.of())
                        .build()))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        final List<ReadyToStartLane> actual = this.ticketRepository.findAllReadyToStartLanes();

        assertThat(actual)
                .extracting(ReadyToStartLane::getLaneId)
                .contains(laneId);
    }

    private void awaitAnalyzerLanesStarted(final Duration timeout) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            final TicketDocument ticket = this.testManager.mongo()
                    .get(TicketDocument.class)
                    .hasSize(1)
                    .singleElement()
                    .assertEntity();
            final boolean allAnalyzerLanesStarted = ticket.getLanes().stream()
                    .filter(lane -> Objects.equals(Agent.ANALYZER, lane.getType()))
                    .allMatch(lane -> Objects.equals(LaneStatus.IN_PROGRESS, lane.getStatus())
                            || Objects.equals(LaneStatus.COMPLETED, lane.getStatus()));
            if (allAnalyzerLanesStarted) {
                return;
            }
            sleepBriefly();
        }
        fail("Analyzer lanes were not started within %s".formatted(timeout));
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(100);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for analyzer lanes to start");
        }
    }
}
