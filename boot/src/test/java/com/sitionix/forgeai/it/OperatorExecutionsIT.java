package com.sitionix.forgeai.it;

import com.sitionix.forgeai.domain.model.laneexecution.LaneExecutionStatus;
import com.sitionix.forgeai.infrastructure.mongodb.entity.laneexecution.LaneExecutionDocument;
import com.sitionix.forgeai.infrastructure.mongodb.repository.laneexecution.LaneExecutionJpaRepository;
import com.sitionix.forgeai.it.infra.ItCodexSessionRepositoryStub;
import com.sitionix.forgeai.it.infra.TestManager;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest(properties = {
        "forge-ai.jobs.scheduling-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class OperatorExecutionsIT {

    @Autowired
    private TestManager testManager;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LaneExecutionJpaRepository laneExecutionJpaRepository;

    @Autowired
    private ItCodexSessionRepositoryStub codexSessionRepositoryStub;

    @Test
    @DisplayName("Should expose execution status via operator endpoint")
    void givenExecutionPersisted_whenGetExecution_thenReturnOperatorView() throws Exception {
        final UUID executionId = UUID.fromString("dddddddd-1111-1111-1111-111111111111");
        this.laneExecutionJpaRepository.save(this.executionDocument(executionId, LaneExecutionStatus.TURN_RUNNING));

        this.mockMvc.perform(get("/api/v1/forge-ai/operator/executions/{executionId}", executionId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.status").value("TURN_RUNNING"))
                .andExpect(jsonPath("$.processPid").value(91342))
                .andExpect(jsonPath("$.codexSessionId").value("session-" + executionId))
                .andExpect(jsonPath("$.codexThreadId").value("thr-" + executionId))
                .andExpect(jsonPath("$.activeTurnId").value("turn-" + executionId))
                .andExpect(jsonPath("$.activeStepId").value("scope_slicing"))
                .andExpect(jsonPath("$.stopCommand").value("just forge-ai-stop-execution " + executionId));
    }

    @Test
    @DisplayName("Should interrupt active execution via operator endpoint")
    void givenActiveExecution_whenInterrupt_thenSendTurnInterruptAndPersistInterruptedStatus() throws Exception {
        final UUID executionId = UUID.fromString("eeeeeeee-1111-1111-1111-111111111111");
        this.laneExecutionJpaRepository.save(this.executionDocument(executionId, LaneExecutionStatus.TURN_RUNNING));

        this.mockMvc.perform(post("/api/v1/forge-ai/operator/executions/{executionId}/interrupt", executionId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.status").value("INTERRUPTED"));

        assertThat(this.codexSessionRepositoryStub.interruptedTurns()).contains("turn-" + executionId);
        final LaneExecutionDocument updated = this.laneExecutionJpaRepository.findById(executionId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(LaneExecutionStatus.INTERRUPTED);
    }

    private LaneExecutionDocument executionDocument(final UUID executionId, final LaneExecutionStatus status) {
        final LaneExecutionDocument document = new LaneExecutionDocument();
        document.setId(executionId);
        document.setTicketId(UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111"));
        document.setLaneId(UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222"));
        document.setAgentId("analyzer");
        document.setScope("automationservice-sox");
        document.setStrategyId("analyzer");
        document.setStrategyVersion(1);
        document.setStatus(status);
        document.setSessionId("session-" + executionId);
        document.setThreadId("thr-" + executionId);
        document.setActiveTurnId("turn-" + executionId);
        document.setProcessPid(91342L);
        document.setProcessCommand("codex app-server --stdio");
        document.setProcessCwd("/workspace");
        document.setCodexVersion("fake");
        document.setProcessStartedAt(LocalDateTime.now().minusSeconds(30));
        document.setCurrentStepId("scope_slicing");
        document.setCurrentStepOrder(1);
        document.setCurrentStepTitle("Scope slicing");
        document.setLastProgressEvent("TURN_STARTED");
        document.setLastProgressAt(LocalDateTime.now().minusSeconds(5));
        document.setLastCodexEventType("TURN_STARTED");
        document.setStartedAt(LocalDateTime.now().minusMinutes(1));
        document.setUpdatedAt(LocalDateTime.now().minusSeconds(5));
        document.setStderrTail(List.of("stderr line"));
        return document;
    }
}
