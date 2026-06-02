package com.sitionix.forgeai.it;

import com.sitionix.forgeai.application.job.ReadyToStartLaneJob;
import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneExecutionDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneStepExecutionDocument;
import com.sitionix.forgeai.infrastructure.mongodb.repository.laneexecution.LaneStepExecutionJpaRepository;
import com.sitionix.forgeai.it.infra.ItCodexSessionRepositoryStub;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge.ai.supervised-execution.enabled=true",
        "forge.ai.supervised-execution.agents[0]=api"
})
class SupervisedApiLaneExecutionIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private ReadyToStartLaneJob readyToStartLaneJob;

    @Autowired
    private LaneStepExecutionJpaRepository laneStepExecutionJpaRepository;

    @Autowired
    private ItCodexSessionRepositoryStub codexSessionRepositoryStub;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @MockBean
    private CodexClient codexClient;

    @Test
    @DisplayName("Should execute API lane via supervised strategy and persist step DONE markers")
    void givenReadyApiLane_whenSupervisorEnabled_thenPersistLaneAndStepExecutions() {
        // given
        this.codexSessionRepositoryStub.clearStartedMessages();
        this.testManager.mongo().create(TicketDocument.class).body("readyToStartApiOnlySeedTicket.json");

        // when
        this.readyToStartLaneJob.run();

        // then
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
        assertThat(this.codexSessionRepositoryStub.startedMessages())
                .singleElement()
                .satisfies(message -> {
                    assertThat(message).contains("Supervised lane session started.");
                    assertThat(message).contains("Agent instruction:");
                    assertThat(message).contains("# Common Agent Rules");
                });

        verifyNoInteractions(this.codexClient);
    }
}
