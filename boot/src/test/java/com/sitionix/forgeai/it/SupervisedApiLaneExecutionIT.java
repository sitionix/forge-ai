package com.sitionix.forgeai.it;

import com.sitionix.forgeai.application.job.ReadyToStartLaneJob;
import com.sitionix.forgeai.application.laneexecution.validation.LaneStepEvidenceValidatorRegistry;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneExecutionDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneStepExecutionDocument;
import com.sitionix.forgeai.infrastructure.mongodb.repository.TicketJpaRepository;
import com.sitionix.forgeai.infrastructure.mongodb.repository.laneexecution.LaneExecutionJpaRepository;
import com.sitionix.forgeai.infrastructure.mongodb.repository.laneexecution.LaneStepExecutionJpaRepository;
import com.sitionix.forgeai.it.infra.ItCodexSessionRepositoryStub;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class SupervisedApiLaneExecutionIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private ReadyToStartLaneJob readyToStartLaneJob;

    @Autowired
    private LaneStepExecutionJpaRepository laneStepExecutionJpaRepository;

    @Autowired
    private LaneExecutionJpaRepository laneExecutionJpaRepository;

    @Autowired
    private TicketJpaRepository ticketJpaRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ItCodexSessionRepositoryStub codexSessionRepositoryStub;

    @MockBean
    private LaneStepEvidenceValidatorRegistry laneStepEvidenceValidatorRegistry;

    @Test
    @DisplayName("Should execute API lane via supervised turn protocol and persist step results")
    void givenReadyApiLane_whenSupervisorRuns_thenPersistLaneAndStepExecutions() throws Exception {
        this.codexSessionRepositoryStub.clearStartedMessages();
        this.codexSessionRepositoryStub.clearSentMessages();
        this.testManager.mongo().create(TicketDocument.class).body("readyToStartApiOnlySeedTicket.json");

        this.readyToStartLaneJob.run();
        this.awaitApiLaneCompletion(Duration.ofSeconds(5));

        this.testManager.mongo()
                .get(LaneExecutionDocument.class)
                .hasSize(1)
                .singleElement()
                .andExpected(execution -> "api".equals(execution.getAgentId())
                        && "completion".equals(execution.getCurrentStepId()));

        this.testManager.mongo()
                .get(LaneStepExecutionDocument.class)
                .hasSize(6);

        final List<LaneStepExecutionDocument> steps = this.laneStepExecutionJpaRepository.findAll();
        assertThat(steps).allMatch(LaneStepExecutionDocument::isDone);
        assertThat(steps.stream().map(LaneStepExecutionDocument::getStepId).toList())
                .containsExactlyInAnyOrder("preparation", "contract_changes", "version_update", "pr", "generation", "completion");

        assertThat(this.codexSessionRepositoryStub.sentMessages()).hasSize(6);
        assertThat(this.codexSessionRepositoryStub.sentMessages().getFirst())
                .contains("START_PROMPT")
                .contains("STEP_PROMPT")
                .contains("- stepId: preparation")
                .contains("- agentId: api")
                .contains("JSON result contract:");
        assertThat(this.codexSessionRepositoryStub.sentMessages().get(1))
                .contains("STEP_PROMPT")
                .contains("- stepId: contract_changes")
                .contains("- agentId: api")
                .doesNotContain("START_PROMPT");

        final TicketDocument ticket = this.ticketJpaRepository.findAll().getFirst();
        this.mockMvc.perform(get("/api/v1/forge-ai/operator/tickets/{ticketId}", ticket.getId())
                        .param("verbosity", "minimal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentEvents[?(@.eventType == 'STEP_STARTED' && @.stepId == 'preparation' && @.stepOrder == 1 && @.totalSteps == 6)]").isNotEmpty())
                .andExpect(jsonPath("$.recentEvents[?(@.eventType == 'STEP_PERSISTED' && @.stepId == 'preparation' && @.stepOrder == 1 && @.totalSteps == 6)]").isNotEmpty())
                .andExpect(jsonPath("$.recentEvents[?(@.eventType == 'STEP_STARTED' && @.stepId == 'contract_changes' && @.stepOrder == 2 && @.totalSteps == 6)]").isNotEmpty())
                .andExpect(jsonPath("$.recentEvents[?(@.eventType == 'STEP_PERSISTED' && @.stepId == 'contract_changes' && @.stepOrder == 2 && @.totalSteps == 6)]").isNotEmpty());
    }

    private void awaitApiLaneCompletion(final Duration timeout) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            final List<LaneExecutionDocument> executions = this.laneExecutionJpaRepository.findAll();
            final List<LaneStepExecutionDocument> steps = this.laneStepExecutionJpaRepository.findAll();
            if (executions.size() == 1
                    && "completion".equals(executions.getFirst().getCurrentStepId())
                    && steps.size() == 6
                    && steps.stream().allMatch(LaneStepExecutionDocument::isDone)
                    && this.codexSessionRepositoryStub.sentMessages().size() == 6) {
                return;
            }
            sleepBriefly();
        }
        fail("API supervised execution did not complete within %s".formatted(timeout));
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(100);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for API supervised execution");
        }
    }
}
