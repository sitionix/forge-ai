package com.sitionix.forgeai.it;

import com.sitionix.forgeai.application.job.ReadyToStartLaneJob;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneExecutionDocument;
import com.sitionix.forgeai.it.infra.ItCodexSessionRepositoryStub;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false"
})
class SupervisedApiFeatureFlagOffIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private ReadyToStartLaneJob readyToStartLaneJob;

    @Autowired
    private ItCodexSessionRepositoryStub codexSessionRepositoryStub;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @Test
    @DisplayName("Should execute API lane via supervised session without a feature flag")
    void givenReadyApiLane_whenSchedulerRuns_thenPersistSupervisedExecutionRecords() {
        this.codexSessionRepositoryStub.clearStartedMessages();
        this.testManager.mongo().create(TicketDocument.class).body("readyToStartApiOnlySeedTicket.json");

        this.readyToStartLaneJob.run();

        this.testManager.mongo()
                .get(LaneExecutionDocument.class)
                .hasSize(1);
        assertThat(this.codexSessionRepositoryStub.startedMessages()).hasSize(1);
        assertThat(this.codexSessionRepositoryStub.startedMessages().getFirst())
                .contains("START_PROMPT")
                .contains("STEP_PROMPT")
                .contains("startContext:")
                .contains("commonInstructionRefs:")
                .contains("shared/common-rules.md")
                .contains("runtimeStepFile:")
                .doesNotContain("# Common Agent Rules")
                .doesNotContain("Lazy Instruction Strategy")
                .hasSizeLessThan(1500);
    }
}
