package com.sitionix.forgeai.application.agentexecutor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.lanecompletion.ScopeMismatchException;
import com.sitionix.forgeai.domain.model.ticket.AgentTicket;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import com.sitionix.forgeai.domain.usecase.CompleteAgentTasks;
import com.sitionix.forgeai.domain.usecase.CreateAgentTask;
import com.sitionix.forgeai.domain.usecase.ValidateApiLaneEvidence;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LaneCompletionSupportTest {

    private LaneCompletionSupport laneCompletionSupport;

    @Mock
    private CompleteAgentTasks completeAgentTasks;

    @Mock
    private CreateAgentTask createAgentTask;

    @Mock
    private ValidateApiLaneEvidence validateApiLaneEvidence;

    @Mock
    private LaneRepository laneRepository;

    @Mock
    private LaneCompletionContractResolver laneCompletionContractResolver;

    @BeforeEach
    void setUp() {
        this.laneCompletionSupport = new LaneCompletionSupport(
                this.completeAgentTasks,
                this.createAgentTask,
                this.validateApiLaneEvidence,
                this.laneRepository,
                this.laneCompletionContractResolver,
                new ObjectMapper()
        );
    }

    @Test
    void givenProducedOutputWithMismatchedNestedPayloadScope_whenValidateProducedLaneInputs_thenReject() {
        final UUID sourceLaneId = UUID.randomUUID();
        final ReadyToStartLane sourceLane = ReadyToStartLane.builder()
                .laneId(sourceLaneId)
                .agent(Agent.ANALYZER)
                .scope("automationservice-sox")
                .build();
        final Lane targetLane = Lane.builder()
                .id(UUID.randomUUID())
                .agent(Agent.ARCHITECT)
                .scope("automationservice-sox")
                .build();
        when(this.laneRepository.findCompletionTargetLanes(sourceLaneId)).thenReturn(List.of(targetLane));
        doReturn(ArchitectPayload.class)
                .when(this.laneCompletionContractResolver)
                .inputPayloadType(Agent.ANALYZER, Agent.ARCHITECT);

        final Map<String, Object> completionPayload = Map.of(
                "outputs",
                List.of(Map.of(
                        "agent", "architect",
                        "scope", "automationservice-sox",
                        "required", true,
                        "payload", Map.of(
                                "scope", "wrong-scope",
                                "requirements", Set.of("requirement")
                        )
                ))
        );

        assertThatThrownBy(() -> this.laneCompletionSupport.validateProducedLaneInputs(sourceLane, completionPayload))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessageContaining("expected=automationservice-sox, actual=wrong-scope");
    }

    @Test
    @SuppressWarnings("unchecked")
    void givenGlobalTargetLane_whenCompleteProducedLaneInputs_thenPayloadScopeMatchesSourceScope() {
        final UUID sourceLaneId = UUID.randomUUID();
        final ReadyToStartLane sourceLane = ReadyToStartLane.builder()
                .laneId(sourceLaneId)
                .ticketId(UUID.randomUUID())
                .agent(Agent.ARCHITECT)
                .scope("backendforfrontendservice-sox")
                .build();
        final Lane targetLane = Lane.builder()
                .id(UUID.randomUUID())
                .agent(Agent.API)
                .scope(ScopeMode.GLOBAL_SCOPE)
                .build();
        when(this.laneRepository.findCompletionTargetLanes(sourceLaneId)).thenReturn(List.of(targetLane));
        doReturn(ApiPayload.class)
                .when(this.laneCompletionContractResolver)
                .inputPayloadType(Agent.ARCHITECT, Agent.API);

        final Map<String, Object> completionPayload = Map.of(
                "outputs",
                List.of(Map.of(
                        "agent", "api",
                        "scope", ScopeMode.GLOBAL_SCOPE,
                        "required", true,
                        "payload", Map.of(
                                "required", true,
                                "reason", "BFF API endpoints required",
                                "scope", "backendforfrontendservice-sox",
                                "summary", "Add BFF flow endpoints",
                                "operations", List.of(),
                                "consumers", Set.of("sitionix-spa"),
                                "notes", Set.of("Generated client required")
                        )
                ))
        );

        this.laneCompletionSupport.completeProducedLaneInputs(sourceLane, completionPayload);

        verify(this.completeAgentTasks).complete(eq(sourceLaneId), argThat(tickets -> {
            final AgentTicket<?> ticket = tickets.iterator().next();
            return ticket.getAgent() == Agent.API
                    && ScopeMode.GLOBAL_SCOPE.equals(ticket.getScope())
                    && ticket.getPayload() instanceof ApiPayload payload
                    && "backendforfrontendservice-sox".equals(payload.getScope());
        }));
    }
}
