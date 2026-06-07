package com.sitionix.forgeai.it;

import com.sitionix.forgeai.application.job.ReadyToStartLaneJob;
import com.sitionix.forgeai.application.laneexecution.validation.LaneStepEvidenceValidatorRegistry;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneExecutionDocument;
import com.sitionix.forgeai.infrastructure.mongodb.repository.laneexecution.LaneExecutionJpaRepository;
import com.sitionix.forgeai.it.infra.ItCodexSessionRepositoryStub;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class SupervisedApiDefaultExecutionIT extends AbstractForgeAiIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private ReadyToStartLaneJob readyToStartLaneJob;

    @Autowired
    private ItCodexSessionRepositoryStub codexSessionRepositoryStub;

    @Autowired
    private LaneExecutionJpaRepository laneExecutionJpaRepository;

    @MockBean
    private LaneStepEvidenceValidatorRegistry laneStepEvidenceValidatorRegistry;

    @Test
    @DisplayName("Should execute API lane via supervised session by default")
    void givenReadyApiLane_whenSchedulerRuns_thenPersistSupervisedExecutionRecords() {
        this.codexSessionRepositoryStub.clearStartedMessages();
        this.codexSessionRepositoryStub.clearSentMessages();
        this.testManager.mongo().create(TicketDocument.class).body("readyToStartApiOnlySeedTicket.json");

        this.readyToStartLaneJob.run();
        this.awaitLaneExecutionRecords(Duration.ofSeconds(5));

        this.testManager.mongo()
                .get(LaneExecutionDocument.class)
                .hasSize(1);
        assertThat(this.codexSessionRepositoryStub.sentMessages()).isNotEmpty();
        assertThat(this.codexSessionRepositoryStub.sentMessages().getFirst())
                .contains("START_PROMPT")
                .contains("STEP_PROMPT")
                .contains("- stepId: preparation")
                .contains("- agentId: api")
                .contains("JSON result contract:");
    }

    private void awaitLaneExecutionRecords(final Duration timeout) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (this.laneExecutionJpaRepository.count() == 1
                    && !this.codexSessionRepositoryStub.sentMessages().isEmpty()) {
                return;
            }
            sleepBriefly();
        }
        fail("Lane execution records were not persisted within %s".formatted(timeout));
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(100);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for lane execution records");
        }
    }
}
