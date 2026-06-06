package com.sitionix.forgeai.it;

import com.sitionix.forgeai.application.job.ReadyToStartLaneJob;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.infrastructure.mongodb.entity.LaneDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import static org.assertj.core.api.Assertions.fail;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=100")
class ReadyToStartLaneJobIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private ReadyToStartLaneJob readyToStartLaneJob;

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
