package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.model.codex.AgentExecutionInput;
import com.sitionix.forgeai.application.job.ReadyToStartLaneJob;
import com.sitionix.forgeai.domain.port.CodexClient;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.CodexCliCommandBuilder;
import com.sitionix.forgeai.infrastructure.codexcli.adapter.TerminalTabLauncher;
import com.sitionix.forgeai.infrastructure.mongodb.entity.AgentTicketDocument;
import com.sitionix.forgeai.infrastructure.mongodb.entity.TicketDocument;
import com.sitionix.forgeai.it.infra.ControllerEndpoint;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false",
        "forge-ai.jobs.ready-to-start.fixed-delay-ms=100"
})
class ReadyToStartImplementBeLaneJobIT {

    @Autowired
    private TestManager testManager;

    @MockBean
    private TerminalTabLauncher terminalTabLauncher;

    @MockBean
    private CodexCliCommandBuilder codexCliCommandBuilder;

    @MockBean
    private CodexClient codexClient;

    @Autowired
    private ReadyToStartLaneJob readyToStartLaneJob;

    @Test
    @DisplayName("Should execute ready implement_be lane by scheduler job")
    void givenReadyImplementBeLane_whenSchedulerRuns_thenSubmitImplementBeInputAndMoveLaneInProgress() {
        //given
        final UUID ticketId = UUID.fromString("51111111-1111-1111-1111-111111111111");
        final UUID implementBeLaneId = UUID.fromString("52222222-2222-2222-2222-222222222222");
        final UUID testUnitLaneId = UUID.fromString("53333333-3333-3333-3333-333333333333");
        final UUID testItLaneId = UUID.fromString("54444444-4444-4444-4444-444444444444");

        this.testManager.mongo()
                .create(TicketDocument.class)
                .body("completeImplementBeLaneSeedTicket.json");

        this.testManager.mockMvc()
                .ping(ControllerEndpoint.completeImplementBeLane())
                .withRequest("requestCompleteImplementBeLane.json")
                .withPathParameters(PathParams.create().add("ticketId", ticketId).add("laneId", implementBeLaneId))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.laneId").value(implementBeLaneId.toString()))
                .andExpectPath(MockMvcResultMatchers.jsonPath("$.status").value("OK"))
                .assertDefault();

        this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1);
        this.testManager.mongo()
                .get(AgentTicketDocument.class)
                .hasSize(2);

        doAnswer(invocation -> {
            final AgentExecutionInput<?> input = invocation.getArgument(0);
            if (Objects.equals(input.getLaneId(), testUnitLaneId)) {
                this.testManager.mockMvc()
                        .ping(ControllerEndpoint.completeUnitTestLane())
                        .withPathParameters(PathParams.create()
                                .add("ticketId", ticketId)
                                .add("laneId", testUnitLaneId))
                        .assertDefault();
                return null;
            }
            if (Objects.equals(input.getLaneId(), testItLaneId)) {
                this.testManager.mockMvc()
                        .ping(ControllerEndpoint.completeItTestLane())
                        .withPathParameters(PathParams.create()
                                .add("ticketId", ticketId)
                                .add("laneId", testItLaneId))
                        .assertDefault();
                return null;
            }
            throw new AssertionError("Unexpected Codex submit laneId=" + input.getLaneId() + ", input=" + input);
        }).when(this.codexClient).submit(any(AgentExecutionInput.class), anyString());

        //when
        this.readyToStartLaneJob.run();

        //then
        this.testManager.mongo()
                .assertEntities(AgentTicketDocument.class)
                .ignoreFields("id", "ticketId", "laneId", "createdAt", "updatedAt")
                .hasSize(2);

        final TicketDocument actual = this.testManager.mongo()
                .get(TicketDocument.class)
                .hasSize(1)
                .singleElement()
                .assertEntity();
        assertThat(actual.getLanes().stream().filter(lane -> Objects.equals(lane.getId(), testUnitLaneId)).findFirst().orElseThrow().getStatus().name())
                .isEqualTo("COMPLETED");
        assertThat(actual.getLanes().stream().filter(lane -> Objects.equals(lane.getId(), testItLaneId)).findFirst().orElseThrow().getStatus().name())
                .isEqualTo("COMPLETED");
    }
}
