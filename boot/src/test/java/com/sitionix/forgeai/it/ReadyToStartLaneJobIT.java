package com.sitionix.forgeai.it;

import com.sitionix.forgeai.infrastructure.mongodb.entity.LaneDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.util.List;
import java.util.Objects;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=100")
class ReadyToStartLaneJobIT {

    @Autowired
    private TestManager testManager;
    @Test
    @DisplayName("Should move analyzer lanes to in progress by scheduler job")
    void givenStartForgeRequest_whenSchedulerRuns_thenMoveAnalyzerLanesToInProgress() {
        //when
        this.testManager.mockMvc()
                .ping(ControllerEndpoint.startForge())
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticket").value("SITIONIX-1"))
                .assertDefault();

        //then
        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(actual -> {
                    final List<LaneDocument> lanes = actual.getLanes();
                    return lanes.stream()
                            .filter(lane -> Objects.equals(Agent.ANALYZER, lane.getType()))
                            .allMatch(lane -> Objects.equals(LaneStatus.READY_TO_START, lane.getStatus())
                                    || Objects.equals(LaneStatus.IN_PROGRESS, lane.getStatus()));
                });
    }
}
