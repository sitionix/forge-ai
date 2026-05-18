package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentLane;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateAgentTaskUseCaseTest {

    @Mock
    private AgentTicketRepository agentTicketRepository;

    @Mock
    private LaneRepository laneRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private CompleteAgentLane completeAgentLane;

    private CreateAgentTaskUseCase createAgentTaskUseCase;

    @BeforeEach
    void setUp() {
        this.createAgentTaskUseCase = new CreateAgentTaskUseCase(
                this.agentTicketRepository,
                this.laneRepository,
                this.ticketRepository,
                this.completeAgentLane
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.agentTicketRepository, this.laneRepository, this.ticketRepository, this.completeAgentLane);
    }

    @Test
    void givenAgentTicket_whenCreate_thenAssignInputTaskAndCompleteSourceLane() {
        //given
        final UUID sourceLaneId = UUID.randomUUID();
        final UUID producedLaneId = UUID.randomUUID();
        final UUID ticketId = UUID.randomUUID();
        final AgentTicket<ArchitectPayload> agentTicket = AgentTicket.<ArchitectPayload>builder()
                .id(ticketId)
                .scope("automationservice-sox")
                .agent(Agent.ARCHITECT)
                .status(AgentTicketStatus.CREATED)
                .payload(ArchitectPayload.builder().build())
                .build();
        final Lane lane = Lane.builder().id(producedLaneId).build();

        when(this.laneRepository.findLaneToProduceOptional(sourceLaneId, "automationservice-sox", Agent.ARCHITECT)).thenReturn(Optional.of(lane));
        when(this.agentTicketRepository.save(agentTicket)).thenReturn(agentTicket);

        //when
        this.createAgentTaskUseCase.create(agentTicket, sourceLaneId);

        //then
        assertThat(agentTicket.getLaneId()).isEqualTo(producedLaneId);
        assertThat(agentTicket.getCreatedAt()).isNotNull();
        assertThat(agentTicket.getUpdatedAt()).isNotNull();
        verify(this.laneRepository).findLaneToProduceOptional(sourceLaneId, "automationservice-sox", Agent.ARCHITECT);
        verify(this.agentTicketRepository).save(agentTicket);
        verify(this.laneRepository).assignInputTaskId(producedLaneId, ticketId);
        verify(this.completeAgentLane).completeAndPrepareAgents(sourceLaneId);
    }

    @Test
    void givenScopeAndAgent_whenMarkAsNotNeeded_thenUpdateProducedLaneStatusAndCompleteSourceLane() {
        //given
        final UUID sourceLaneId = UUID.randomUUID();
        final UUID producedLaneId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(producedLaneId).build();
        when(this.laneRepository.findLaneToProduceOptional(sourceLaneId, "GLOBAL", Agent.API)).thenReturn(Optional.of(lane));

        //when
        this.createAgentTaskUseCase.markAsNotNeeded(sourceLaneId, "GLOBAL", Agent.API);

        //then
        verify(this.laneRepository).findLaneToProduceOptional(sourceLaneId, "GLOBAL", Agent.API);
        verify(this.ticketRepository).updateLaneStatus(producedLaneId, LaneStatus.NOT_NEEDED);
        verify(this.completeAgentLane).completeAndPrepareAgents(sourceLaneId);
    }

    @Test
    void givenLaneNotFound_whenCreate_thenThrowIllegalStateException() {
        //given
        final UUID sourceLaneId = UUID.randomUUID();
        final AgentTicket<ArchitectPayload> agentTicket = AgentTicket.<ArchitectPayload>builder()
                .id(UUID.randomUUID())
                .scope("backendforfrontendservice-sox")
                .agent(Agent.ARCHITECT)
                .status(AgentTicketStatus.CREATED)
                .payload(ArchitectPayload.builder().build())
                .build();
        when(this.laneRepository.findLaneToProduceOptional(sourceLaneId, "backendforfrontendservice-sox", Agent.ARCHITECT))
                .thenReturn(Optional.empty());

        //when //then
        assertThatThrownBy(() -> this.createAgentTaskUseCase.create(agentTicket, sourceLaneId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sourceLaneId=" + sourceLaneId)
                .hasMessageContaining("scope=backendforfrontendservice-sox")
                .hasMessageContaining("agent=ARCHITECT");
        verify(this.laneRepository).findLaneToProduceOptional(sourceLaneId, "backendforfrontendservice-sox", Agent.ARCHITECT);
    }

    @Test
    void givenLaneNotFound_whenMarkAsNotNeeded_thenThrowIllegalStateException() {
        //given
        final UUID sourceLaneId = UUID.randomUUID();
        when(this.laneRepository.findLaneToProduceOptional(sourceLaneId, "GLOBAL", Agent.API)).thenReturn(Optional.empty());

        //when //then
        assertThatThrownBy(() -> this.createAgentTaskUseCase.markAsNotNeeded(sourceLaneId, "GLOBAL", Agent.API))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sourceLaneId=" + sourceLaneId)
                .hasMessageContaining("scope=GLOBAL")
                .hasMessageContaining("agent=API");
        verify(this.laneRepository).findLaneToProduceOptional(sourceLaneId, "GLOBAL", Agent.API);
    }
}
