package com.sitionix.forgeai.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItCompletionPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.repository.AgentTicketJpaRepository;
import com.sitionix.forgeai.it.infra.AgentTicketJsonFixture;
import com.sitionix.forgeai.it.infra.LaneCompletionTestFacade;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest(properties = "forge-ai.jobs.ready-to-start.fixed-delay-ms=600000")
class CompleteItTestLaneFlowIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private LaneCompletionTestFacade laneCompletion;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentTicketJpaRepository agentTicketJpaRepository;

    @Test
    @DisplayName("Should store integration test report and complete test_it lane")
    void givenCompleteItTestPayload_whenCompleteItTestLane_thenStoreReportAndCompleteLane() {
        //given
        final UUID ticketId = UUID.fromString("81111111-1111-1111-1111-111111111111");
        final UUID laneId = UUID.fromString("82222222-2222-2222-2222-222222222222");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeItTestLaneSeedTicket.json");
        AgentTicketJsonFixture.insert(
                "completeItTestLaneSeedAgentTicket.json",
                this.objectMapper,
                this.agentTicketJpaRepository
        );

        //when then
        this.laneCompletion.completeItTestLane(ticketId, laneId);

        final List<AgentTicketDocument> agentTickets = this.agentTicketJpaRepository.findAll();
        assertThat(agentTickets).hasSize(2);
        assertThat(agentTickets)
                .anySatisfy(ticket -> {
                    assertThat(ticket.getId()).isEqualTo(UUID.fromString("94444444-4444-4444-4444-444444444444"));
                    assertThat(ticket.getStatus()).isEqualTo(AgentTicketStatus.CREATED);
                    assertThat(ticket.getAgent()).isEqualTo(Agent.TEST_IT);
                    assertThat(ticket.getPayload()).isInstanceOf(TestItPayload.class);
                    assertThat(((TestItPayload) ticket.getPayload()).getTask())
                            .isEqualTo("Prepare integration test execution context");
                })
                .anySatisfy(ticket -> {
                    assertThat(ticket.getStatus()).isEqualTo(AgentTicketStatus.CONSUMED);
                    assertThat(ticket.getAgent()).isEqualTo(Agent.TEST_IT);
                    assertThat(ticket.getPayload()).isInstanceOf(TestItCompletionPayload.class);
                    assertThat(((TestItCompletionPayload) ticket.getPayload()).getSummary())
                            .isEqualTo("Completed integration tests for backend flow.");
                });

        this.testManager.mongo()
                .assertEntities(TicketDocument.class)
                .ignoreFields("id", "createdAt", "updatedAt", "attempt", "inputTaskIds")
                .hasSize(1)
                .containsWithJsonsStrict("expectedCompleteItTestLaneTicket.json");
    }
}
