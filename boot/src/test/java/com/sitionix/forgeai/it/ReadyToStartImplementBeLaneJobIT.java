package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePayload;
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
import java.util.Objects;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@IntegrationTest(properties = {
        "spring.task.scheduling.enabled=false",
        "forge-ai.jobs.ready-to-start.fixed-delay-ms=100"
})
class ReadyToStartImplementBeLaneJobIT {

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
    @DisplayName("Should execute ready implement_be lane by scheduler job")
    void givenReadyImplementBeLane_whenSchedulerRuns_thenSubmitImplementBeInputAndMoveLaneInProgress() {
        //given
        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("readyToStartImplementBeJobSeedTicket.json");
        this.testManager.mongo()
                .create(AgentTicketDocument.class)
                .body(new AgentTicketDocument(
                        UUID.fromString("15333333-3333-3333-3333-333333333333"),
                        UUID.fromString("15111111-1111-1111-1111-111111111111"),
                        UUID.fromString("15222222-2222-2222-2222-222222222222"),
                        AgentTicketStatus.CREATED,
                        "automationservice-sox",
                        Agent.IMPLEMENT_BE,
                        ImplementBePayload.builder()
                                .task("implement task")
                                .scope("automationservice-sox")
                                .summary("summary")
                                .requirements(Set.of("r1"))
                                .constraints(Set.of("c1"))
                                .nonGoals(Set.of("n1"))
                                .architectureDecision("decision")
                                .dependencies(Set.of("d1"))
                                .acceptanceNotes(Set.of("a1"))
                                .risks(Set.of("risk1"))
                                .build(),
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

        final ImplementBePayload expectedTask = ImplementBePayload.builder()
                .task("implement task")
                .scope("automationservice-sox")
                .summary("summary")
                .requirements(Set.of("r1"))
                .constraints(Set.of("c1"))
                .nonGoals(Set.of("n1"))
                .architectureDecision("decision")
                .dependencies(Set.of("d1"))
                .acceptanceNotes(Set.of("a1"))
                .risks(Set.of("risk1"))
                .build();
        if (inputCaptor.getAllValues().stream().noneMatch(actual -> Objects.equals(actual.getTasks(), Set.of(expectedTask)))) {
            throw new AssertionError("Unexpected implement_be tasks: " + inputCaptor.getAllValues());
        }

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(value -> value.getLanes().stream()
                        .anyMatch(lane -> Objects.equals(lane.getId(), java.util.UUID.fromString("15222222-2222-2222-2222-222222222222"))
                                && Objects.equals(LaneStatus.IN_PROGRESS, lane.getStatus())));
    }
}
