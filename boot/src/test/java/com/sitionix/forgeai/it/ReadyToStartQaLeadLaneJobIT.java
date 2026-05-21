package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.application.job.ReadyToStartLaneJob;
import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@IntegrationTest(properties = {
        "spring.task.scheduling.enabled=false",
        "forge-ai.jobs.ready-to-start.fixed-delay-ms=100"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ReadyToStartQaLeadLaneJobIT {

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
    @DisplayName("Should execute ready qa_lead lane by scheduler job")
    void givenReadyQaLeadLane_whenSchedulerRuns_thenSubmitQaLeadInputAndMoveLaneInProgress() {
        //given
        final QaLeadPayload payload = new QaLeadPayload();
        payload.setRequirements(Set.of("sr1"));
        payload.setConstraints(Set.of("qc1"));
        payload.setNonGoals(Set.of("qn1"));
        payload.setRisks(Set.of("qr1"));
        payload.setDependencies(Set.of("qd1"));
        payload.setQualityFocus(Set.of("qf1"));
        payload.setEdgeConsiderations(Set.of("qe1"));

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("readyToStartQaLeadJobSeedTicket.json");
        this.testManager.mongo()
                .create(AgentTicketDocument.class)
                .body(new AgentTicketDocument(
                        UUID.fromString("25333333-3333-3333-3333-333333333333"),
                        UUID.fromString("25111111-1111-1111-1111-111111111111"),
                        UUID.fromString("25222222-2222-2222-2222-222222222222"),
                        AgentTicketStatus.CREATED,
                        "automationservice-sox",
                        Agent.QA_LEAD,
                        payload,
                        LocalDateTime.parse("2026-01-01T10:00:00"),
                        LocalDateTime.parse("2026-01-01T10:00:00")));

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1);
        this.testManager.mongo()
                .get(AgentTicketDocument.class)
                .hasSize(1);
        final ArgumentCaptor<AgentExecutionInput> inputCaptor = ArgumentCaptor.forClass(AgentExecutionInput.class);

        //when
        this.readyToStartLaneJob.run();

        //then
        verify(this.codexClient, org.mockito.Mockito.atLeastOnce())
                .submit(inputCaptor.capture(), eq("/dev/ttys999"));

        final QaLeadPayload expectedTask = payload;
        if (inputCaptor.getAllValues().stream().noneMatch(actual -> Objects.equals(actual.getTasks(), Set.of(expectedTask)))) {
            throw new AssertionError("Unexpected qa_lead tasks: " + inputCaptor.getAllValues());
        }

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), java.util.UUID.fromString("25222222-2222-2222-2222-222222222222"))
                                && Objects.equals(LaneStatus.IN_PROGRESS, lane.getStatus())));
    }
}
