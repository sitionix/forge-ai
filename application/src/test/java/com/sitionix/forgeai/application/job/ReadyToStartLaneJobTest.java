package com.sitionix.forgeai.application.job;

import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ExecuteAgent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.props.AgentPropertiesProvider;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.ManageTicketOperatorRuns;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.core.task.TaskExecutor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadyToStartLaneJobTest {

    private ReadyToStartLaneJob readyToStartLaneJob;

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private ManageTicketOperatorRuns manageTicketOperatorRuns;
    private TaskExecutor taskExecutor;

    @BeforeEach
    void setUp() {
        this.taskExecutor = Runnable::run;
        this.readyToStartLaneJob = new ReadyToStartLaneJob(this.ticketRepository, this.manageTicketOperatorRuns, this.taskExecutor);
    }

    @AfterEach
    void tearDown() {
        for (final Agent value : Agent.values()) {
            value.setExecutor(null);
            value.setInfo(null);
        }
        verifyNoMoreInteractions(this.ticketRepository);
    }

    @Test
    void givenReadyLanes_whenRun_thenExecuteEachAgent() {
        //given
        final ExecuteAgent<AgentTicketPayload> analyzerExecutor = mock(ExecuteAgent.class);
        final AgentPropertiesProvider.AgentConfigView analyzerConfig = mock(AgentPropertiesProvider.AgentConfigView.class);
        when(analyzerConfig.isEnabled()).thenReturn(true);
        Agent.ANALYZER.setExecutor(analyzerExecutor);
        Agent.ANALYZER.setInfo(analyzerConfig);

        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(UUID.randomUUID())
                .laneId(UUID.randomUUID())
                .agent(Agent.ANALYZER)
                .scope("automationservice-sox")
                .serviceId("atmssox")
                .build();
        when(this.ticketRepository.findAllReadyToStartLanes()).thenReturn(List.of(lane));
        when(this.ticketRepository.moveLaneToInProgressIfReady(lane.getLaneId())).thenReturn(true);
        when(this.manageTicketOperatorRuns.isExecutionBlocked(lane.getTicketId())).thenReturn(false);

        //when
        this.readyToStartLaneJob.run();

        //then
        verify(this.ticketRepository).findAllReadyToStartLanes();
        verify(this.ticketRepository).moveLaneToInProgressIfReady(lane.getLaneId());
        verify(this.manageTicketOperatorRuns, times(2)).isExecutionBlocked(lane.getTicketId());
        verify(analyzerConfig).isEnabled();
        verify(analyzerExecutor).executeLane(lane);
        verifyNoMoreInteractions(analyzerConfig, analyzerExecutor, this.manageTicketOperatorRuns);
    }
}
