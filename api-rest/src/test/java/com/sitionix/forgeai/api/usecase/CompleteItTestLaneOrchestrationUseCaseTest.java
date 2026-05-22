package com.sitionix.forgeai.api.usecase;

import com.app_afesox.fgaisox.api_first.dto.CompleteItTestLaneRequestDTO;
import com.sitionix.forgeai.api.LaneCompletionValidator;
import com.sitionix.forgeai.api.RequestValidationException;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketStatus;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItCompletionPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentLane;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompleteItTestLaneOrchestrationUseCaseTest {

    private CompleteItTestLaneOrchestrationUseCase completeItTestLaneOrchestrationUseCase;

    @Mock
    private LaneCompletionValidator laneCompletionValidator;

    @Mock
    private AgentTicketRepository agentTicketRepository;

    @Mock
    private CompleteAgentLane completeAgentLane;

    @BeforeEach
    void setUp() {
        this.completeItTestLaneOrchestrationUseCase = new CompleteItTestLaneOrchestrationUseCase(
                this.laneCompletionValidator,
                this.agentTicketRepository,
                this.completeAgentLane
        );
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.laneCompletionValidator, this.agentTicketRepository, this.completeAgentLane);
    }

    @Test
    void givenValidItCompletion_whenComplete_thenStoreReportAndCompleteLane() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final String scope = "automationservice-sox";
        final String summary = "Completed integration tests for backend flow.";
        final List<String> coveredCases = List.of(
                "Create agent action successfully",
                "Reject create agent action for unknown agent"
        );
        final CompleteItTestLaneRequestDTO request = mock(CompleteItTestLaneRequestDTO.class);
        when(request.getScope()).thenReturn(scope);
        when(request.getSummary()).thenReturn(summary);
        when(request.getCoveredCases()).thenReturn(coveredCases);

        //when
        this.completeItTestLaneOrchestrationUseCase.complete(ticketId, laneId, request);

        //then
        verify(this.laneCompletionValidator).validateItTestCompletion(ticketId, laneId, scope);
        final ArgumentCaptor<AgentTicket<TestItCompletionPayload>> reportCaptor = ArgumentCaptor.forClass(AgentTicket.class);
        verify(this.agentTicketRepository).save(reportCaptor.capture());
        verify(this.completeAgentLane).completeAndPrepareAgents(laneId);

        final AgentTicket<TestItCompletionPayload> actual = reportCaptor.getValue();
        assertThat(actual.getTicketId()).isEqualTo(ticketId);
        assertThat(actual.getLaneId()).isEqualTo(laneId);
        assertThat(actual.getStatus()).isEqualTo(AgentTicketStatus.CONSUMED);
        assertThat(actual.getScope()).isEqualTo(scope);
        assertThat(actual.getAgent()).isEqualTo(Agent.TEST_IT);
        assertThat(actual.getCreatedAt()).isNotNull();
        assertThat(actual.getUpdatedAt()).isNotNull();
        assertThat(actual.getPayload()).isEqualTo(TestItCompletionPayload.builder()
                .scope(scope)
                .summary(summary)
                .coveredCases(coveredCases)
                .build());
    }

    @Test
    void givenEmptyCoveredCases_whenComplete_thenThrowRequestValidationException() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteItTestLaneRequestDTO request = mock(CompleteItTestLaneRequestDTO.class);
        when(request.getCoveredCases()).thenReturn(List.of());

        //when //then
        assertThatThrownBy(() -> this.completeItTestLaneOrchestrationUseCase.complete(ticketId, laneId, request))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("coveredCases must not be empty");
        verifyNoMoreInteractions(this.laneCompletionValidator, this.agentTicketRepository, this.completeAgentLane);
    }

    @Test
    void givenBlankCoveredCase_whenComplete_thenThrowRequestValidationException() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final CompleteItTestLaneRequestDTO request = mock(CompleteItTestLaneRequestDTO.class);
        when(request.getCoveredCases()).thenReturn(List.of("Create agent action successfully", " "));

        //when //then
        assertThatThrownBy(() -> this.completeItTestLaneOrchestrationUseCase.complete(ticketId, laneId, request))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("coveredCases must not contain blank values");
        verifyNoMoreInteractions(this.laneCompletionValidator, this.agentTicketRepository, this.completeAgentLane);
    }
}
