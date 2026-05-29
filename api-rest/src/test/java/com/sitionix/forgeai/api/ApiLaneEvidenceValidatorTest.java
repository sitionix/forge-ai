package com.sitionix.forgeai.api;

import com.app_afesox.fgaisox.api_first.dto.ApiLaneDependencyEvidence;
import com.app_afesox.fgaisox.api_first.dto.ApiLaneEvidence;
import com.app_afesox.fgaisox.api_first.dto.CompleteApiLaneRequest;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.repository.AgentTicketRepository;
import com.sitionix.forgeai.domain.repository.TicketRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiLaneEvidenceValidatorTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private AgentTicketRepository agentTicketRepository;

    private ApiLaneEvidenceValidator validator;

    @BeforeEach
    void setUp() {
        this.validator = new ApiLaneEvidenceValidator(this.ticketRepository, this.agentTicketRepository);
    }

    @Test
    void givenMissingRequiredScopeEvidence_whenValidate_thenThrowScopeMismatchException() {
        final UUID laneId = UUID.randomUUID();
        final UUID inputTaskId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).inputTaskIds(Set.of(inputTaskId)).build();
        final ApiPayload payload = ApiPayload.builder().required(true).scope("backendforfrontendservice-sox").build();
        final AgentTicket<ApiPayload> ticket = AgentTicket.<ApiPayload>builder().payload(payload).build();
        final CompleteApiLaneRequest request = CompleteApiLaneRequest.builder()
                .evidence(ApiLaneEvidence.builder().prUrl("https://github.com/sitionix/app-afesox/pull/164").dependencies(Set.of(
                        ApiLaneDependencyEvidence.builder().scope("automationservice-sox").runId(1L).build()
                ).stream().toList()).build())
                .build();

        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));
        when(this.agentTicketRepository.findById(inputTaskId, ApiPayload.class)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> this.validator.validateRequiredScopeDependencies(laneId, request))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessageContaining("missing required dependency scopes");
    }

    @Test
    void givenRequiredScopeEvidencePresentWithExtraScopes_whenValidate_thenPass() {
        final UUID laneId = UUID.randomUUID();
        final UUID inputTaskId = UUID.randomUUID();
        final Lane lane = Lane.builder().id(laneId).inputTaskIds(Set.of(inputTaskId)).build();
        final ApiPayload payload = ApiPayload.builder().required(true).scope("backendforfrontendservice-sox").build();
        final AgentTicket<ApiPayload> ticket = AgentTicket.<ApiPayload>builder().payload(payload).build();
        final CompleteApiLaneRequest request = CompleteApiLaneRequest.builder()
                .evidence(ApiLaneEvidence.builder().prUrl("https://github.com/sitionix/app-afesox/pull/164").dependencies(
                        java.util.List.of(
                                ApiLaneDependencyEvidence.builder().scope("backendforfrontendservice-sox").runId(11L).build(),
                                ApiLaneDependencyEvidence.builder().scope("automationservice-sox").runId(12L).build()
                        )
                ).build())
                .build();

        when(this.ticketRepository.findByLaneId(laneId)).thenReturn(Optional.of(lane));
        when(this.agentTicketRepository.findById(inputTaskId, ApiPayload.class)).thenReturn(Optional.of(ticket));

        assertThatCode(() -> this.validator.validateRequiredScopeDependencies(laneId, request))
                .doesNotThrowAnyException();
    }
}
