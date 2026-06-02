package com.sitionix.forgeai.application.laneexecution.support;

import com.sitionix.forgeai.domain.model.codex.CodexSessionStartCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnCommand;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FakeInteractiveCodexSessionRepositoryTest {

    @Test
    void givenSubmittedTurn_whenResponsePlannerReturnsJson_thenHistoryRecordsAssistantResponse() {
        final FakeInteractiveCodexSessionRepository repository = new FakeInteractiveCodexSessionRepository(command -> """
                {
                  "type": "LANE_STEP_DONE",
                  "stepId": "scope_slicing",
                  "summary": "done",
                  "evidence": {}
                }
                """);

        final String sessionId = repository.openSession(CodexSessionStartCommand.builder().workspaceRoot(".").build()).id();
        final String response = repository.submitTurn(sessionId, CodexTurnCommand.builder()
                        .prompt("STEP_PROMPT step=scope_slicing")
                        .timeout(Duration.ofSeconds(1))
                        .build())
                .assistantResponse();

        assertThat(response).contains("\"stepId\": \"scope_slicing\"");
        assertThat(repository.history(sessionId)).containsExactly(
                "service:STEP_PROMPT step=scope_slicing",
                "assistant:" + response
        );
        assertThat(repository.submittedPrompts()).containsExactly("STEP_PROMPT step=scope_slicing");
    }
}
