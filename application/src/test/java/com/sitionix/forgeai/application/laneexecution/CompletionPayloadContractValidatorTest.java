package com.sitionix.forgeai.application.laneexecution;

import com.sitionix.forgeai.application.agentexecutor.LaneCompletionContractResolver;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadFieldContract;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadObjectContract;
import com.sitionix.forgeai.domain.model.lanecompletion.contract.CompletionPayloadValueType;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.QaLeadPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
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
    private final FakeCompletionPayloadContractRepository contractRepository = new FakeCompletionPayloadContractRepository();
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
        return payload;
    }

    private Map<String, Object> qaLeadPayload() {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", "automationservice-sox");
        payload.put("requirements", List.of("Cover API contract."));
        payload.put("qualityFocus", List.of("Contract compatibility."));
        return payload;
    }

    private static final class FakeLaneRepository implements LaneRepository {

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
            return List.of(
                    Lane.builder().id(UUID.randomUUID()).agent(Agent.ARCHITECT).scope("automationservice-sox").build(),
                    Lane.builder().id(UUID.randomUUID()).agent(Agent.QA_LEAD).scope("automationservice-sox").build()
            );
        }
    }

    private static final class FakeContractResolver implements LaneCompletionContractResolver {

        @Override
        public Class<? extends AgentTicketPayload> inputPayloadType(final Agent sourceAgent, final Agent targetAgent) {
            return switch (targetAgent) {
                case ARCHITECT -> ArchitectPayload.class;
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

    private static final class FakeCompletionPayloadContractRepository implements CompletionPayloadContractRepository {

        @Override
        public CompletionPayloadObjectContract findByType(final Class<?> payloadType) {
            return this.findByTypeName(payloadType.getSimpleName());
        }

        @Override
        public CompletionPayloadObjectContract findByTypeName(final String payloadType) {
            return switch (payloadType) {
                case "ArchitectPayload" -> new CompletionPayloadObjectContract(
                        payloadType,
                        "Architect task input.",
                        List.of(
                                field("scope", CompletionPayloadValueType.STRING),
                                field("requirements", CompletionPayloadValueType.ARRAY)
                        )
                );
                case "QaLeadPayload" -> new CompletionPayloadObjectContract(
                        payloadType,
                        "QA lead task input.",
                        List.of(
                                field("scope", CompletionPayloadValueType.STRING),
                                field("requirements", CompletionPayloadValueType.ARRAY),
                                field("qualityFocus", CompletionPayloadValueType.ARRAY)
                        )
                );
                default -> throw new IllegalArgumentException("Unexpected payloadType=" + payloadType);
            };
        }

        private static CompletionPayloadFieldContract field(final String name, final CompletionPayloadValueType type) {
            return new CompletionPayloadFieldContract(
                    name,
                    type,
                    true,
                    "Test field.",
                    type == CompletionPayloadValueType.ARRAY ? CompletionPayloadValueType.STRING : null,
                    null,
                    null
            );
        }
    }
}
