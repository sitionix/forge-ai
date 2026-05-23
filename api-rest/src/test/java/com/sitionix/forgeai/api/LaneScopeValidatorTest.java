package com.sitionix.forgeai.api;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.LaneStatus;
import com.sitionix.forgeai.domain.model.ticket.Ticket;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LaneScopeValidatorTest {

    private LaneScopeValidator laneScopeValidator;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private LaneRepository laneRepository;

    @BeforeEach
    void setUp() {
        this.laneScopeValidator = new LaneScopeValidator(this.ticketRepository, this.laneRepository);
    }

    @Test
    void givenAnalyzerLaneAndDifferentPayloadScope_whenValidateAnalyzerCallbackScope_thenThrowScopeMismatchException() {
        //given
        final UUID laneId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).scope("backendforfrontendservice-sox").build();
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        //when //then
        assertThatThrownBy(() -> this.laneScopeValidator.validateAnalyzerCallbackScope(laneId, "automationservice-sox", "automationservice-sox"))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessageContaining("Analyzer callback scope mismatch");

        verify(this.ticketRepository).findByLaneId(laneId);
    }

    @Test
    void givenArchitectLaneAndDifferentImplementationScope_whenValidateArchitectCallbackScope_thenThrowScopeMismatchException() {
        //given
        final UUID laneId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).scope("automationservice-sox").build();
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        //when //then
        assertThatThrownBy(() -> this.laneScopeValidator.validateArchitectCallbackScope(laneId, "backendforfrontendservice-sox"))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessageContaining("Implementation scope mismatch");

        verify(this.ticketRepository).findByLaneId(laneId);
    }

    @Test
    void givenImplementBeLaneAndDifferentImplementationScope_whenValidateImplementBeCallbackScope_thenThrowScopeMismatchException() {
        //given
        final UUID laneId = UUID.randomUUID();
        final Lane lane = this.getLane(laneId, Agent.IMPLEMENT_BE, "automationservice-sox", LaneStatus.IN_PROGRESS);
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        //when //then
        assertThatThrownBy(() -> this.laneScopeValidator.validateImplementBeCallbackScope(laneId, "backendforfrontendservice-sox"))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessageContaining("Implement-be scope mismatch");

        verify(this.ticketRepository).findByLaneId(laneId);
    }

    @Test
    void givenQaLeadLaneAndDifferentTestScope_whenValidateQaLeadCallbackScope_thenThrowScopeMismatchException() {
        //given
        final UUID laneId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).scope("automationservice-sox").build();
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        //when //then
        assertThatThrownBy(() -> this.laneScopeValidator.validateQaLeadCallbackScope(laneId, "backendforfrontendservice-sox"))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessageContaining("QA lead scope mismatch");

        verify(this.ticketRepository).findByLaneId(laneId);
    }

    @Test
    void givenUnitTestLaneAndDifferentTestScope_whenValidateUnitTestCallbackScope_thenThrowScopeMismatchException() {
        //given
        final UUID laneId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).scope("automationservice-sox").build();
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        //when //then
        assertThatThrownBy(() -> this.laneScopeValidator.validateUnitTestCallbackScope(laneId, "backendforfrontendservice-sox"))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessageContaining("Unit-test scope mismatch");

        verify(this.ticketRepository).findByLaneId(laneId);
    }

    @Test
    void givenApiLaneProducedImplementationScopesAndUnexpectedContractScope_whenValidateApiCallbackScopes_thenThrowScopeMismatchException() {
        //given
        final UUID laneId = UUID.randomUUID();
        final Lane implementBeLane = Lane.builder().id(UUID.randomUUID()).scope("automationservice-sox").agent(Agent.IMPLEMENT_BE).build();
        final Lane implementFeLane = Lane.builder().id(UUID.randomUUID()).scope("backendforfrontendservice-sox").agent(Agent.IMPLEMENT_FE).build();
        when(this.laneRepository.findProducedLanes(laneId)).thenReturn(List.of(implementBeLane, implementFeLane));

        //when //then
        this.laneScopeValidator.resolveRelevantApiScopes(laneId, Set.of("automationservice-sox", "unknown-service"));

        verify(this.laneRepository).findProducedLanes(laneId);
    }

    @Test
    void givenValidScopesForAllCallbacks_whenValidate_thenNoException() {
        //given
        final UUID analyzerLaneId = UUID.randomUUID();
        final UUID architectLaneId = UUID.randomUUID();
        final UUID implementBeLaneId = UUID.randomUUID();
        final UUID qaLeadLaneId = UUID.randomUUID();
        final UUID unitTestLaneId = UUID.randomUUID();
        final UUID apiLaneId = UUID.randomUUID();
        when(this.ticketRepository.findByLaneId(analyzerLaneId))
                .thenReturn(Optional.of(Lane.builder().id(analyzerLaneId).scope("automationservice-sox").build()));
        when(this.ticketRepository.findByLaneId(architectLaneId))
                .thenReturn(Optional.of(Lane.builder().id(architectLaneId).scope("automationservice-sox").build()));
        when(this.ticketRepository.findByLaneId(implementBeLaneId))
                .thenReturn(Optional.of(this.getLane(implementBeLaneId, Agent.IMPLEMENT_BE, "automationservice-sox", LaneStatus.IN_PROGRESS)));
        when(this.ticketRepository.findByLaneId(qaLeadLaneId))
                .thenReturn(Optional.of(this.getLane(qaLeadLaneId, Agent.QA_LEAD, "automationservice-sox", LaneStatus.IN_PROGRESS)));
        when(this.ticketRepository.findByLaneId(unitTestLaneId))
                .thenReturn(Optional.of(this.getLane(unitTestLaneId, Agent.TEST_UNIT, "automationservice-sox", LaneStatus.IN_PROGRESS)));
        when(this.laneRepository.findProducedLanes(apiLaneId)).thenReturn(List.of(
                Lane.builder().id(UUID.randomUUID()).scope("automationservice-sox").agent(Agent.IMPLEMENT_BE).build(),
                Lane.builder().id(UUID.randomUUID()).scope("backendforfrontendservice-sox").agent(Agent.IMPLEMENT_FE).build()
        ));

        //when
        this.laneScopeValidator.validateAnalyzerCallbackScope(analyzerLaneId, "automationservice-sox", "automationservice-sox");
        this.laneScopeValidator.validateArchitectCallbackScope(architectLaneId, "automationservice-sox");
        this.laneScopeValidator.validateImplementBeCallbackScope(implementBeLaneId, "automationservice-sox");
        this.laneScopeValidator.validateQaLeadCallbackScope(qaLeadLaneId, "automationservice-sox");
        this.laneScopeValidator.validateUnitTestCallbackScope(unitTestLaneId, "automationservice-sox");
        this.laneScopeValidator.resolveRelevantApiScopes(apiLaneId, Set.of("automationservice-sox", "backendforfrontendservice-sox"));

        //then
        verify(this.ticketRepository).findByLaneId(analyzerLaneId);
        verify(this.ticketRepository).findByLaneId(architectLaneId);
        verify(this.ticketRepository).findByLaneId(implementBeLaneId);
        verify(this.ticketRepository).findByLaneId(qaLeadLaneId);
        verify(this.ticketRepository).findByLaneId(unitTestLaneId);
        verify(this.laneRepository).findProducedLanes(apiLaneId);
        verifyNoMoreInteractions(this.ticketRepository, this.laneRepository);
    }

    @Test
    void givenApiLaneProducedImplementationScopesAndNoIntersection_whenResolveRelevantApiScopes_thenThrowScopeMismatchException() {
        //given
        final UUID laneId = UUID.randomUUID();
        final Lane implementBeLane = Lane.builder().id(UUID.randomUUID()).scope("automationservice-sox").agent(Agent.IMPLEMENT_BE).build();
        when(this.laneRepository.findProducedLanes(laneId)).thenReturn(List.of(implementBeLane));

        //when //then
        assertThatThrownBy(() -> this.laneScopeValidator.resolveRelevantApiScopes(laneId, Set.of("unknown-service")))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessageContaining("does not contain contracts for produced implementation scopes");

        verify(this.laneRepository).findProducedLanes(laneId);
    }

    @Test
    void givenMissingTicket_whenValidateItTestCompletion_thenThrowNotFound() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.empty());

        //when //then
        assertThatThrownBy(() -> this.laneScopeValidator.validateItTestCompletion(ticketId, laneId, "automationservice-sox"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(this.ticketRepository).findById(ticketId);
        verifyNoMoreInteractions(this.ticketRepository, this.laneRepository);
    }

    @Test
    void givenWrongLaneType_whenValidateQaLeadCompletion_thenThrowConflict() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final Lane lane = this.getLane(laneId, Agent.TEST_UNIT, "automationservice-sox", LaneStatus.IN_PROGRESS);
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.of(this.getTicket(ticketId, lane)));

        //when //then
        assertThatThrownBy(() -> this.laneScopeValidator.validateQaLeadCompletion(ticketId, laneId, "automationservice-sox"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("lane type mismatch");
        verify(this.ticketRepository).findById(ticketId);
        verifyNoMoreInteractions(this.ticketRepository, this.laneRepository);
    }

    @Test
    void givenWrongScope_whenValidateItTestCompletion_thenThrowScopeMismatchException() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final Lane lane = this.getLane(laneId, Agent.TEST_IT, "automationservice-sox", LaneStatus.IN_PROGRESS);
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.of(this.getTicket(ticketId, lane)));

        //when //then
        assertThatThrownBy(() -> this.laneScopeValidator.validateItTestCompletion(ticketId, laneId, "backendforfrontendservice-sox"))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessageContaining("IT test scope mismatch");
        verify(this.ticketRepository).findById(ticketId);
        verifyNoMoreInteractions(this.ticketRepository, this.laneRepository);
    }

    @Test
    void givenCompletedLane_whenValidateItTestCompletion_thenThrowConflict() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        final Lane lane = this.getLane(laneId, Agent.TEST_IT, "automationservice-sox", LaneStatus.COMPLETED);
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.of(this.getTicket(ticketId, lane)));

        //when //then
        assertThatThrownBy(() -> this.laneScopeValidator.validateItTestCompletion(ticketId, laneId, "automationservice-sox"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("lane cannot be completed in current state");
        verify(this.ticketRepository).findById(ticketId);
        verifyNoMoreInteractions(this.ticketRepository, this.laneRepository);
    }

    @Test
    void givenImplementFeScopeMismatch_whenValidateAgentCallbackScope_thenThrowScopeMismatch() {
        //given
        final UUID laneId = UUID.randomUUID();
        final Lane lane = this.getLane(laneId, Agent.IMPLEMENT_FE, "sitionix-spa", LaneStatus.IN_PROGRESS);
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        //when //then
        assertThatThrownBy(() -> this.laneScopeValidator.validateAgentCallbackScope(laneId, "automationservice-sox", Agent.IMPLEMENT_FE, "Implement-fe"))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessageContaining("Implement-fe scope mismatch");
        verify(this.ticketRepository).findByLaneId(laneId);
        verifyNoMoreInteractions(this.ticketRepository, this.laneRepository);
    }

    @Test
    void givenWrongLaneType_whenValidateAgentCallbackScope_thenThrowScopeMismatch() {
        //given
        final UUID laneId = UUID.randomUUID();
        final Lane lane = this.getLane(laneId, Agent.IMPLEMENT_BE, "sitionix-spa", LaneStatus.IN_PROGRESS);
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));

        //when //then
        assertThatThrownBy(() -> this.laneScopeValidator.validateAgentCallbackScope(laneId, "sitionix-spa", Agent.IMPLEMENT_FE, "Implement-fe"))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessageContaining("lane type mismatch");
        verify(this.ticketRepository).findByLaneId(laneId);
        verifyNoMoreInteractions(this.ticketRepository, this.laneRepository);
    }

    @Test
    void givenMissingLane_whenValidateAgentCallbackScope_thenThrowScopeMismatch() {
        //given
        final UUID laneId = UUID.randomUUID();
        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.empty());

        //when //then
        assertThatThrownBy(() -> this.laneScopeValidator.validateAgentCallbackScope(laneId, "sitionix-spa", Agent.IMPLEMENT_FE, "Implement-fe"))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessageContaining("Implement-fe lane not found");
        verify(this.ticketRepository).findByLaneId(laneId);
        verifyNoMoreInteractions(this.ticketRepository, this.laneRepository);
    }

    @Test
    void givenMissingLaneInTicket_whenValidateItTestCompletion_thenThrowNotFound() {
        //given
        final UUID ticketId = UUID.randomUUID();
        final UUID laneId = UUID.randomUUID();
        when(this.ticketRepository.findById(ticketId)).thenReturn(Optional.of(this.getTicket(ticketId, this.getLane(UUID.randomUUID(), Agent.TEST_IT, "automationservice-sox", LaneStatus.IN_PROGRESS))));

        //when //then
        assertThatThrownBy(() -> this.laneScopeValidator.validateItTestCompletion(ticketId, laneId, "automationservice-sox"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(this.ticketRepository).findById(ticketId);
        verifyNoMoreInteractions(this.ticketRepository, this.laneRepository);
    }

    private Ticket getTicket(final UUID ticketId, final Lane lane) {
        return Ticket.builder()
                .id(ticketId)
                .ticketKey("ticket-key")
                .taskDescription("task")
                .lanes(List.of(lane))
                .build();
    }

    private Lane getLane(final UUID laneId, final Agent agent, final String scope, final LaneStatus status) {
        return Lane.builder()
                .id(laneId)
                .agent(agent)
                .scope(scope)
                .status(status)
                .build();
    }
}
