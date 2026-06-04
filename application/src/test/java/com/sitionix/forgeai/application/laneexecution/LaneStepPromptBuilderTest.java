package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.agentexecutor.LaneCompletionContractResolver;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.ScopeContext;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ArchitectPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.model.ticket.lane.AgentInstructions;
import com.sitionix.forgeai.domain.model.ticket.lane.Lane;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import com.sitionix.forgeai.domain.repository.LaneRepository;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LaneStepPromptBuilderTest {

    private final FakeLaneRepository laneRepository = new FakeLaneRepository();
    private final FakeLaneCompletionContractResolver laneCompletionContractResolver = new FakeLaneCompletionContractResolver();

    private final LaneStepPromptBuilder laneStepPromptBuilder = new LaneStepPromptBuilder(
            () -> List.of("shared/common-rules.md"),
            new FakeInstructionRepository(),
            this.laneRepository,
            this.laneCompletionContractResolver,
            new ObjectMapper()
    );

    @Test
    void buildStartPrompt_includesMetadataTasksScopeAndResolvedCommonInstructions() {
        final String prompt = this.laneStepPromptBuilder.buildStartPrompt(this.lane(), this.strategy(), this.input());

        assertThat(prompt).contains("START_PROMPT");
        assertThat(prompt).contains("ticketId:");
        assertThat(prompt).contains("ticketKey:");
        assertThat(prompt).contains("laneId:");
        assertThat(prompt).contains("agentId:");
        assertThat(prompt).contains("scope:");
        assertThat(prompt).contains("Task payloads:");
        assertThat(prompt).contains("Scope context:");
        assertThat(prompt).contains("### shared/common-rules.md");
        assertThat(prompt).contains("resolved::shared/common-rules.md");
        assertThat(prompt).contains("Return exactly one JSON object");
    }

    @Test
    void buildStepPrompt_usesYamlStepAndResolvedInstructionText() {
        final String prompt = this.laneStepPromptBuilder.buildStepPrompt(this.lane(), this.strategy(), this.strategy().getSteps().getFirst(), this.input(), 1, 3);

        assertThat(prompt).contains("STEP_PROMPT");
        assertThat(prompt).contains("stepIndex: 1");
        assertThat(prompt).contains("stepId: scope_slicing");
        assertThat(prompt).contains("### lane-instructions/analyzer/scope-slicing.md");
        assertThat(prompt).contains("resolved::lane-instructions/analyzer/scope-slicing.md");
        assertThat(prompt).doesNotContain("architect-handoff.md");
        assertThat(prompt).doesNotContain("qa-lead-handoff.md");
    }

    @Test
    void buildCorrectionPrompt_forFinalStep_includesCompletionContract() {
        final LaneStrategyStep completionStep = this.strategy().getSteps().getLast();
        final String prompt = this.laneStepPromptBuilder.buildCorrectionPrompt(this.lane(), completionStep, "summary must be non-empty", true);

        assertThat(prompt).contains("CORRECTION_PROMPT");
        assertThat(prompt).contains("Active step id: completion");
        assertThat(prompt).contains("Validation error: summary must be non-empty");
        assertThat(prompt).contains("completionPayload");
    }

    @Test
    void buildStepPrompt_forFinalStep_rendersProducedLaneOutputsWithoutLegacyHandoffFields() {
        this.laneCompletionContractResolver.registerInputPayloadType(Agent.ARCHITECT, ArchitectPayload.class);
        this.laneRepository.producedLanes = List.of(Lane.builder()
                .id(UUID.randomUUID())
                .agent(Agent.ARCHITECT)
                .scope("automationservice-sox")
                .build());

        final String prompt = this.laneStepPromptBuilder.buildStepPrompt(
                this.lane(),
                this.strategy(),
                this.strategy().getSteps().getLast(),
                this.input(),
                3,
                3
        );

        assertThat(prompt).contains("\"outputs\"");
        assertThat(prompt).contains("\"agent\": \"architect\"");
        assertThat(prompt).contains("\"scope\": \"automationservice-sox\"");
        assertThat(prompt).contains("\"payload\"");
        assertThat(prompt).doesNotContain("architectHandoff");
        assertThat(prompt).doesNotContain("qaLeadHandoff");
    }

    private ReadyToStartLane lane() {
        return ReadyToStartLane.builder()
                .ticketId(UUID.randomUUID())
                .ticketKey("SITIONIX-1")
                .laneId(UUID.randomUUID())
                .agent(Agent.ANALYZER)
                .scope("backendforfrontendservice-sox")
                .serviceId("bffssox")
                .sourceTerminalTty("/dev/ttys001")
                .attempt(1)
                .build();
    }

    private LaneStrategy strategy() {
        return LaneStrategy.builder()
                .agentId("analyzer")
                .version(1)
                .sessionMode("single_session")
                .steps(List.of(
                        LaneStrategyStep.builder().id("scope_slicing").title("Scope Slicing").order(1).instructionRefs(List.of("lane-instructions/analyzer/scope-slicing.md")).build(),
                        LaneStrategyStep.builder().id("architect_handoff").title("Architect Handoff").order(2).instructionRefs(List.of("lane-instructions/analyzer/architect-handoff.md")).build(),
                        LaneStrategyStep.builder().id("completion").title("Completion").order(3).instructionRefs(List.of("lane-instructions/analyzer/completion-content.md")).build()
                ))
                .build();
    }

    private AgentExecutionInput<AgentTicketPayload> input() {
        final ApiPayload task = ApiPayload.builder()
                .scope("backendforfrontendservice-sox")
                .summary("Implement analyzer task payload support.")
                .build();
        return AgentExecutionInput.<AgentTicketPayload>builder()
                .tasks(new LinkedHashSet<>(Set.of(task)))
                .scope(ScopeContext.builder().scope("backendforfrontendservice-sox").build())
                .build();
    }

    private static final class FakeInstructionRepository implements InstructionRepository {

        @Override
        public AgentInstructions findInstructionsByAgentId(final String agentId) {
            return AgentInstructions.builder()
                    .agentInstruction("resolved::" + agentId)
                    .additionalInstructions(Set.of())
                    .sharedInstructions(Set.of())
                    .build();
        }

        @Override
        public String findInstructionTextByRef(final String instructionRef) {
            return "resolved::" + instructionRef;
        }

        @Override
        public Set<String> findSharedInstructionRefs() {
            return Set.of("shared/common-rules.md");
        }
    }

    private static final class FakeLaneRepository implements LaneRepository {

        private List<Lane> producedLanes = List.of();

        @Override
        public Lane findLaneToProduce(final UUID relatedLaneId,
                                      final String scope,
                                      final Agent agent) {
            return Lane.builder()
                    .id(UUID.randomUUID())
                    .agent(agent)
                    .scope(scope)
                    .build();
        }

        @Override
        public Optional<Lane> findLaneToProduceOptional(final UUID relatedLaneId,
                                                        final String scope,
                                                        final Agent agent) {
            return Optional.empty();
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
            return this.producedLanes;
        }
    }

    private static final class FakeLaneCompletionContractResolver implements LaneCompletionContractResolver {

        private final Map<Agent, Class<? extends AgentTicketPayload>> inputPayloadTypes = new EnumMap<>(Agent.class);

        private void registerInputPayloadType(final Agent targetAgent, final Class<? extends AgentTicketPayload> payloadType) {
            this.inputPayloadTypes.put(targetAgent, payloadType);
        }

        @Override
        public Class<? extends AgentTicketPayload> inputPayloadType(final Agent sourceAgent, final Agent targetAgent) {
            final Class<? extends AgentTicketPayload> payloadType = this.inputPayloadTypes.get(targetAgent);
            if (payloadType == null) {
                throw new AssertionError("No fake payload type configured for targetAgent=" + targetAgent);
            }
            return payloadType;
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
