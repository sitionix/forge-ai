package com.sitionix.forgeai.application.job;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadyToStartLaneJobTest {

    private ReadyToStartLaneJob readyToStartLaneJob;

    @Mock
    private TicketRepository ticketRepository;

    @BeforeEach
    void setUp() {
        this.readyToStartLaneJob = new ReadyToStartLaneJob(this.ticketRepository);
    }

    @AfterEach
    void tearDown() {
        for (final Agent value : Agent.values()) {
            value.setExecutor(null);
        }
        verifyNoMoreInteractions(this.ticketRepository);
    }

    @Test
    void givenReadyLanes_whenRun_thenExecuteEachAgent() {
        //given
        final ExecuteAgent<AgentTicketPayload> analyzerExecutor = mock(ExecuteAgent.class);
        Agent.ANALYZER.setExecutor(analyzerExecutor);

        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(UUID.randomUUID())
                .laneId(UUID.randomUUID())
                .agent(Agent.ANALYZER)
                .scope("automationservice-sox")
                .serviceId("atmssox")
                .build();
        when(this.ticketRepository.findAllReadyToStartLanes()).thenReturn(List.of(lane));

        //when
        this.readyToStartLaneJob.run();

        //then
        verify(this.ticketRepository).findAllReadyToStartLanes();
        verify(analyzerExecutor).executeLane(lane);
        verifyNoMoreInteractions(analyzerExecutor);
    }
}
