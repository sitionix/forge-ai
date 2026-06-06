package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.agentexecutor.LaneCompletionContractResolver;
import com.sitionix.forgeai.domain.model.lanecompletion.ScopeMismatchException;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.model.ticket.lane.ScopeMode;
import com.sitionix.forgeai.domain.repository.CompletionPayloadContractRepository;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompletionPayloadContractValidatorTest {

    private final FakeLaneRepository laneRepository = new FakeLaneRepository();
    private final FakeContractResolver contractResolver = new FakeContractResolver();
    private final CompletionPayloadContractRepository contractRepository =
            new TestCompletionPayloadContractRepository(new ObjectMapper());
    private final CompletionPayloadContractValidator validator = new CompletionPayloadContractValidator(
            new CompletionPayloadContractBuilder(this.laneRepository, this.contractResolver, this.contractRepository),
            this.contractRepository
    );

    @Test
    void givenPayloadMatchingCompletionContract_whenValidate_thenAccept() {
        this.validator.validate(this.lane(), Map.of("outputs", List.of(
                this.output("architect", "automationservice-sox", true, this.architectPayload()),
                this.output("qa_lead", "automationservice-sox", true, this.qaLeadPayload())
        )));
    }

    @Test
    void givenRequiredPayloadFieldMissing_whenValidate_thenReject() {
        final Map<String, Object> payload = this.architectPayload();
        payload.remove("requirements");

        assertThatThrownBy(() -> this.validator.validate(this.lane(), Map.of("outputs", List.of(
                this.output("architect", "automationservice-sox", true, payload)
        ))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required field: outputs.payload.requirements");
    }

    @Test
    void givenUnknownPayloadField_whenValidate_thenReject() {
        final Map<String, Object> payload = this.architectPayload();
        payload.put("unexpected", "value");

        assertThatThrownBy(() -> this.validator.validate(this.lane(), Map.of("outputs", List.of(
                this.output("architect", "automationservice-sox", true, payload)
        ))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown fields at outputs.payload");
    }

    @Test
    void givenUnknownOutputTarget_whenValidate_thenReject() {
        assertThatThrownBy(() -> this.validator.validate(this.lane(), Map.of("outputs", List.of(
                this.output("implement_be", "automationservice-sox", true, this.architectPayload())
        ))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown completion output target");
    }

    @Test
    void givenOptionalOutputBeforeInvalidOutput_whenValidate_thenStillValidatesFollowingOutput() {
        final Map<String, Object> payload = this.qaLeadPayload();
        payload.remove("qualityFocus");

        assertThatThrownBy(() -> this.validator.validate(this.lane(), Map.of("outputs", List.of(
                this.output("architect", "automationservice-sox", false, Map.of()),
                this.output("qa_lead", "automationservice-sox", true, payload)
        ))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required field: outputs.payload.qualityFocus");
    }

    @Test
    void givenPerScopeSourceAndGlobalTarget_whenPayloadScopeMatchesSourceScope_thenAccept() {
        this.laneRepository.targetLanes = List.of(Lane.builder()
                .id(UUID.randomUUID())
                .agent(Agent.API)
                .scope(ScopeMode.GLOBAL_SCOPE)
                .build());

        this.validator.validate(this.architectLane(), Map.of("outputs", List.of(
                this.output("api", ScopeMode.GLOBAL_SCOPE, true, this.apiPayload("backendforfrontendservice-sox"))
        )));
    }

    @Test
    void givenPerScopeSourceAndGlobalTarget_whenPayloadScopeUsesRoutingScope_thenReject() {
        this.laneRepository.targetLanes = List.of(Lane.builder()
                .id(UUID.randomUUID())
                .agent(Agent.API)
                .scope(ScopeMode.GLOBAL_SCOPE)
                .build());

        assertThatThrownBy(() -> this.validator.validate(this.architectLane(), Map.of("outputs", List.of(
                this.output("api", ScopeMode.GLOBAL_SCOPE, true, this.apiPayload(ScopeMode.GLOBAL_SCOPE))
        ))))
                .isInstanceOf(ScopeMismatchException.class)
                .hasMessageContaining("Completion payload scope mismatch: expected=backendforfrontendservice-sox, actual=GLOBAL");
    }

    private ReadyToStartLane lane() {
        return ReadyToStartLane.builder()
                .ticketId(UUID.randomUUID())
                .ticketKey("SITIONIX-1")
                .laneId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                .agent(Agent.ANALYZER)
                .scope("automationservice-sox")
                .serviceId("automationservice-sox")
                .build();
    }

    private ReadyToStartLane architectLane() {
        return ReadyToStartLane.builder()
                .ticketId(UUID.randomUUID())
                .ticketKey("SITIONIX-1")
                .laneId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
                .agent(Agent.ARCHITECT)
                .scope("backendforfrontendservice-sox")
                .serviceId("bffssox")
                .build();
    }

    private Map<String, Object> output(final String agent,
                                       final String scope,
                                       final boolean required,
                                       final Map<String, Object> payload) {
        final Map<String, Object> output = new LinkedHashMap<>();
        output.put("agent", agent);
        output.put("scope", scope);
        output.put("required", required);
        output.put("payload", payload);
        return output;
    }

    private Map<String, Object> architectPayload() {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", "automationservice-sox");
        payload.put("requirements", List.of("Add API contract."));
        payload.put("constraints", List.of("Preserve API-first flow."));
        payload.put("nonGoals", List.of("Do not implement runtime execution."));
        payload.put("risks", List.of("Contract drift."));
        payload.put("dependencies", List.of("BFF client generation."));
        return payload;
    }

    private Map<String, Object> qaLeadPayload() {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", "automationservice-sox");
        payload.put("requirements", List.of("Cover API contract."));
        payload.put("constraints", List.of("Use existing IT style."));
        payload.put("nonGoals", List.of("Do not request UI tests for backend-only work."));
        payload.put("risks", List.of("Regression in API mapping."));
        payload.put("dependencies", List.of("Generated API DTOs."));
        payload.put("qualityFocus", List.of("Contract compatibility."));
        payload.put("edgeConsiderations", List.of("Unknown project."));
        return payload;
    }

    private Map<String, Object> apiPayload(final String scope) {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("required", true);
        payload.put("reason", "BFF API endpoints required");
        payload.put("scope", scope);
        payload.put("summary", "Add BFF flow endpoints");
        payload.put("operations", List.of());
        payload.put("consumers", List.of("sitionix-spa"));
        payload.put("notes", List.of("Generate client from contract"));
        return payload;
    }

    private static final class FakeLaneRepository implements LaneRepository {

        private List<Lane> targetLanes = List.of(
                Lane.builder().id(UUID.randomUUID()).agent(Agent.ARCHITECT).scope("automationservice-sox").build(),
                Lane.builder().id(UUID.randomUUID()).agent(Agent.QA_LEAD).scope("automationservice-sox").build()
        );

        @Override
        public Lane findLaneToProduce(final UUID relatedLaneId, final String scope, final Agent agent) {
            return Lane.builder().id(UUID.randomUUID()).agent(agent).scope(scope).build();
        }

        @Override
        public Optional<Lane> findLaneToProduceOptional(final UUID relatedLaneId, final String scope, final Agent agent) {
            return Optional.of(this.findLaneToProduce(relatedLaneId, scope, agent));
        }

        @Override
        public void assignInputTaskId(final UUID laneId, final UUID inputTaskId) {
        }

        @Override
        public List<Lane> findProducedLanes(final UUID sourceLaneId) {
            return List.of();
        }

        @Override
        public List<Lane> findCompletionTargetLanes(final UUID sourceLaneId) {
            return this.targetLanes;
        }
    }

    private static final class FakeContractResolver implements LaneCompletionContractResolver {

        @Override
        public Class<? extends AgentTicketPayload> inputPayloadType(final Agent sourceAgent, final Agent targetAgent) {
            return switch (targetAgent) {
                case ARCHITECT -> ArchitectPayload.class;
                case API -> ApiPayload.class;
                case QA_LEAD -> QaLeadPayload.class;
                default -> throw new IllegalArgumentException("Unexpected target agent: " + targetAgent);
            };
        }

        @Override
        public boolean writesProducedLaneOutputs(final Agent agent) {
            return true;
        }

        @Override
        public boolean requiresApiCompletionEvidence(final Agent agent) {
            return false;
        }

        @Override
        public boolean requiresCompletionOutputForEveryTarget(final Agent agent) {
            return true;
        }

        @Override
        public Optional<Class<? extends AgentTicketPayload>> completionReportPayloadType(final Agent agent) {
            return Optional.empty();
        }
    }
}
