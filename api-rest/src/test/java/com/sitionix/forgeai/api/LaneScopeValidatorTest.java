package com.sitionix.forgeai.api;

import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
        final Lane lane = Lane.builder().id(laneId).scope("automationservice-sox").build();
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
        final UUID apiLaneId = UUID.randomUUID();
        when(this.ticketRepository.findByLaneId(analyzerLaneId))
                .thenReturn(Optional.of(Lane.builder().id(analyzerLaneId).scope("automationservice-sox").build()));
        when(this.ticketRepository.findByLaneId(architectLaneId))
                .thenReturn(Optional.of(Lane.builder().id(architectLaneId).scope("automationservice-sox").build()));
        when(this.laneRepository.findProducedLanes(apiLaneId)).thenReturn(List.of(
                Lane.builder().id(UUID.randomUUID()).scope("automationservice-sox").agent(Agent.IMPLEMENT_BE).build(),
                Lane.builder().id(UUID.randomUUID()).scope("backendforfrontendservice-sox").agent(Agent.IMPLEMENT_FE).build()
        ));

        //when
        this.laneScopeValidator.validateAnalyzerCallbackScope(analyzerLaneId, "automationservice-sox", "automationservice-sox");
        this.laneScopeValidator.validateArchitectCallbackScope(architectLaneId, "automationservice-sox");
        this.laneScopeValidator.validateImplementBeCallbackScope(architectLaneId, "automationservice-sox");
        this.laneScopeValidator.validateQaLeadCallbackScope(architectLaneId, "automationservice-sox");
        this.laneScopeValidator.validateUnitTestCallbackScope(architectLaneId, "automationservice-sox");
        this.laneScopeValidator.resolveRelevantApiScopes(apiLaneId, Set.of("automationservice-sox", "backendforfrontendservice-sox"));

        //then
        verify(this.ticketRepository).findByLaneId(analyzerLaneId);
        verify(this.ticketRepository, times(4)).findByLaneId(architectLaneId);
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
}
