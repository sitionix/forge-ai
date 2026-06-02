package com.sitionix.forgeai.it;

import com.sitionix.forgeai.application.job.ReadyToStartLaneJob;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneExecutionDocument;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.ItCodexSessionRepositoryStub;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge-ai.jobs.ready-to-start.fixed-delay-ms=100"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ReadyToStartQaLeadLaneJobIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private ItCodexSessionRepositoryStub codexSessionRepositoryStub;

    @Autowired
    private ReadyToStartLaneJob readyToStartLaneJob;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @Test
    @DisplayName("Should execute ready qa_lead lane via supervised session")
    void givenReadyQaLeadLane_whenSchedulerRuns_thenPersistSupervisedLaneExecutions() {
        final UUID ticketId = UUID.fromString("71111111-1111-1111-1111-111111111111");
        final UUID qaLeadLaneId = UUID.fromString("72222222-2222-2222-2222-222222222222");

        this.codexSessionRepositoryStub.clearStartedMessages();
        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeQaLeadLaneBackendSeedTicket.json");

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeQaLeadLaneBackend())
                .withRequest("requestCompleteQaLeadLaneBackend.json")
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", qaLeadLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(qaLeadLaneId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.status").value("OK"))
                .assertDefault();

        this.readyToStartLaneJob.run();

        this.testManager.mongo()
                .assertEntities(LaneExecutionDocument.class)
                .hasSize(2);

        final TicketDocument actual = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();
        assertThat(actual.getLanes().stream().filter(lane -> lane.getId().equals(qaLeadLaneId)).findFirst().orElseThrow().getStatus().name())
                .isEqualTo("COMPLETED");

        assertThat(this.codexSessionRepositoryStub.startedMessages()).hasSize(2);
        assertThat(this.codexSessionRepositoryStub.startedMessages())
                .allSatisfy(message -> {
                    assertThat(message).contains("START_PROMPT");
                    assertThat(message).contains("STEP_PROMPT");
                    assertThat(message).contains("startContext:");
                    assertThat(message).contains("commonInstructionRefs:");
                    assertThat(message).contains("shared/common-rules.md");
                    assertThat(message).contains("runtimeStepFile:");
                    assertThat(message).doesNotContain("# Common Agent Rules");
                    assertThat(message).doesNotContain("Lazy Instruction Strategy");
                    assertThat(message).hasSizeLessThan(1500);
                });
    }
}
