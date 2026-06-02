package com.sitionix.forgeai.application.laneexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.domain.model.codex.ScopeContext;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategy;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyPromptConfig;
import com.sitionix.forgeai.domain.model.laneexecution.LaneStrategyStep;
import com.sitionix.forgeai.domain.model.ticket.AgentTicketPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiPayload;
import com.sitionix.forgeai.domain.model.ticket.lane.Agent;
import com.sitionix.forgeai.domain.model.ticket.lane.ReadyToStartLane;
import com.sitionix.forgeai.domain.repository.InstructionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class SupervisedPromptArtifactWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void givenActiveStep_whenWriteArtifacts_thenRenderOnlyActiveStepAndKeepFutureStepOut() throws Exception {
        final InstructionRepository instructionRepository = Mockito.mock(InstructionRepository.class);
        when(instructionRepository.findInstructionTextByRef("lane-instructions/analyzer/scope-slicing.md"))
                .thenReturn("""
                        # Analyzer Scope Slicing

                        Task payload:
                        {{TASKS_JSON}}

                        Scope context:
                        {{SCOPE_CONTEXT}}
                        """);
        when(instructionRepository.findInstructionTextByRef("lane-instructions/analyzer/architect-handoff.md"))
                .thenReturn("# Analyzer Architect Handoff\n\nFuture step instructions.");
        final LaneStrategyPromptConfig promptConfig = () -> List.of("shared/common-rules.md");

        final SupervisedExecutionProperties supervisedExecutionProperties = new SupervisedExecutionProperties();
        supervisedExecutionProperties.setRuntimeRoot(this.tempDir.resolve("runtime").toString());

        final SupervisedPromptArtifactWriter writer = new SupervisedPromptArtifactWriter(
                new ObjectMapper(),
                instructionRepository,
                promptConfig,
                supervisedExecutionProperties
        );

        final ReadyToStartLane lane = ReadyToStartLane.builder()
                .ticketId(UUID.randomUUID())
                .ticketKey("SITIONIX-1")
                .laneId(UUID.randomUUID())
                .agent(Agent.ANALYZER)
                .scope("backendforfrontendservice-sox")
                .serviceId("bffssox")
                .sourceTerminalTty("/dev/ttys001")
                .attempt(1)
                .build();
        final LaneStrategy strategy = LaneStrategy.builder()
                .agentId("analyzer")
                .version(1)
                .sessionMode("single_session")
                .steps(List.of(
                        LaneStrategyStep.builder().id("scope_slicing").title("Scope Slicing").order(1).taskPlaceholder("TASKS").instructionRefs(List.of("lane-instructions/analyzer/scope-slicing.md")).build(),
                        LaneStrategyStep.builder().id("architect_handoff").title("Architect Handoff").order(2).instructionRefs(List.of("lane-instructions/analyzer/architect-handoff.md")).build()
                ))
                .build();
        final AgentExecutionInput<AgentTicketPayload> input = AgentExecutionInput.<AgentTicketPayload>builder()
                .ticketId(lane.getTicketId())
                .ticket(lane.getTicketKey())
                .laneId(lane.getLaneId())
                .tasks(Set.of(ApiPayload.builder().scope("backendforfrontendservice-sox").summary("Implement analyzer task payload support.").build()))
                .scope(ScopeContext.builder().scope("backendforfrontendservice-sox").build())
                .build();

        final UUID executionId = UUID.randomUUID();
        final Path startContextPath = writer.writeStartContext(lane, strategy, input, executionId);
        final Path stepPath = writer.writeStepInstructionFile(lane, strategy.getSteps().getFirst(), input, executionId);

        assertThat(startContextPath).exists();
        assertThat(stepPath).exists();
        assertThat(Files.readString(startContextPath))
                .contains("commonInstructionRefs")
                .contains("shared/common-rules.md")
                .contains("backendforfrontendservice-sox");
        assertThat(Files.readString(stepPath))
                .contains("# Analyzer Scope Slicing")
                .contains("Task payload:")
                .doesNotContain("{{TASKS_JSON}}")
                .doesNotContain("{{SCOPE_CONTEXT}}")
                .doesNotContain("Future step instructions.");
    }
}
