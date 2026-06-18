package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.model.lanecompletion.ScopeMismatchException;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.LaneCompletionTestFacade;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class CompleteAnalyzerLaneScopeMismatchIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;

    @Test
    @DisplayName("Should fail analyzer completion when output scope does not match produced lane scope")
    void givenBffAnalyzerLane_whenCompleteAnalyzerWithAutomationScope_thenReturnBadRequestAndDoNotCreateTasks() {
        //given
        final UUID ticketId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        final UUID bffAnalyzerLaneId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeAnalyzerLaneScopeMismatchSeedTicket.json");

        //when
        assertThatThrownBy(() -> this.laneCompletion.completeAnalyzerLane(ticketId, bffAnalyzerLaneId))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessage("Completion output scope mismatch: sourceLaneId=bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb, sourceAgent=analyzer, targetAgent=architect, expectedScope=backendforfrontendservice-sox, actualScope=automationservice-sox");

        //then
        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .hasSize(0);
    }
}
