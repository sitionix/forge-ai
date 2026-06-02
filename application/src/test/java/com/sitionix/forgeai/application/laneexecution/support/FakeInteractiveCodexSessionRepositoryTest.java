package com.sitionix.forgeai.application.laneexecution.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FakeInteractiveCodexSessionRepositoryTest {

    @Test
    void givenResponsePlanner_whenSendAndWait_thenReturnOutputsInSameSession() {
        final FakeInteractiveCodexSessionRepository repository = new FakeInteractiveCodexSessionRepository(message -> {
            if (message.contains("step-1")) {
                return java.util.List.of("response-1");
            }
            if (message.contains("step-2")) {
                return java.util.List.of("response-2");
            }
            return java.util.List.of();
        });

        final String sessionId = repository.start("initial prompt", "/dev/ttys001");
        repository.send(sessionId, "step-1 prompt", "/dev/ttys001");
        repository.send(sessionId, "step-2 prompt", "/dev/ttys001");

        assertThat(repository.sessionIds()).containsExactly(sessionId);
        assertThat(repository.waitForOutput(sessionId, 1000)).isEqualTo("response-1");
        assertThat(repository.waitForOutput(sessionId, 1000)).isEqualTo("response-2");
        assertThat(repository.history(sessionId))
                .contains("service:initial prompt")
                .contains("service:step-1 prompt")
                .contains("service:step-2 prompt")
                .contains("output:response-1")
                .contains("output:response-2");
    }
}
