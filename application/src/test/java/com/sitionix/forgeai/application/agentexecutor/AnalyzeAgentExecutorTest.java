package com.sitionix.forgeai.application.agentexecutor;

import com.sitionix.forgeai.application.usecase.PrepareAgentExecutionInputUseCase;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.ScopeContext;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.domain.port.ServicePropertiesProvider;
import com.sitionix.forgeai.domain.port.TicketRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyzeAgentExecutorTest {

    private AnalyzeAgentExecutor analyzeAgentExecutor;

    @Mock
    private PrepareAgentExecutionInputUseCase prepareAgentExecutionInputUseCase;

    @Mock
    private ServicePropertiesProvider props;

    @Mock
    private CodexClient codexClient;

    @Mock
    private TicketRepository ticketRepository;

    @BeforeEach
    void setUp() {
        this.analyzeAgentExecutor = new AnalyzeAgentExecutor(
                this.prepareAgentExecutionInputUseCase,
                this.props,
                this.codexClient,
                this.ticketRepository
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.prepareAgentExecutionInputUseCase, this.props, this.codexClient, this.ticketRepository);
    }

    @Test
    void givenReadyLane_whenExecute_thenSubmitEnrichedInputToCodex() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .agent(Agent.ANALYZER)
                .scope("automationservice-sox")
                .serviceId("atmssox")
                .sourceTerminalTty("/dev/ttys003")
                .build();

        final AgentExecutionInput baseInput = AgentExecutionInput.builder()
                .ticketId(ticketId)
                .laneId(laneId)
                .agentInstruction("instruction")
                .sharedInstructions(Set.of("shared"))
                .build();
        when(this.prepareAgentExecutionInputUseCase.execute(lane)).thenReturn(baseInput);

        final ServicePropertiesProvider.ServiceConfigView serviceConfigView = mock(ServicePropertiesProvider.ServiceConfigView.class);
        when(serviceConfigView.getLabel()).thenReturn("Automation Service SOX");
        when(serviceConfigView.getDomainKeywords()).thenReturn(List.of("automation", "agents"));
        when(serviceConfigView.getTags()).thenReturn(List.of("java", "backend"));
        when(serviceConfigView.getOwnsBusinessAreas()).thenReturn(List.of("Agents"));
        when(this.props.getServices()).thenReturn(Map.of("atmssox", serviceConfigView));

        when(this.ticketRepository.findTicketContentById(ticketId)).thenReturn("task-description");

        //when
        this.analyzeAgentExecutor.execute(lane);

        //then
        verify(this.prepareAgentExecutionInputUseCase).execute(lane);
        verify(this.props).getServices();
        verify(this.ticketRepository).findTicketContentById(ticketId);

        final ArgumentCaptor<AgentExecutionInput> inputCaptor = ArgumentCaptor.forClass(AgentExecutionInput.class);
        verify(this.codexClient).submit(inputCaptor.capture(), eq("/dev/ttys003"));
        final AgentExecutionInput actual = inputCaptor.getValue();

        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getLaneId()).isEqualTo(laneId);
        assertThat(actual.getTicket()).isEqualTo("task-description");
        assertThat(actual.getScope()).isEqualTo(ScopeContext.builder()
                .scope("automationservice-sox")
                .label("Automation Service SOX")
                .domainKeywords(Set.of("automation", "agents"))
                .tags(Set.of("java", "backend"))
                .ownBusinessAreas(Set.of("Agents"))
                .build());

        verify(serviceConfigView).getLabel();
        verify(serviceConfigView).getDomainKeywords();
        verify(serviceConfigView).getTags();
        verify(serviceConfigView).getOwnsBusinessAreas();
        verifyNoMoreInteractions(serviceConfigView);
    }
}
