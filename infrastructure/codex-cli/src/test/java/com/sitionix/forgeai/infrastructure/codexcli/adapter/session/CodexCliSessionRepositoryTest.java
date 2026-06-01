package com.sitionix.forgeai.infrastructure.codexcli.adapter.session;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodexCliSessionRepositoryTest {

    @Test
    void givenInteractiveProcess_whenStartSendAndRead_thenUsesSingleSession() {
        final CodexCliSessionRepository repository = this.repository(List.of(
                "bash", "--noprofile", "--norc", "-c", "echo READY; while IFS= read -r line; do echo \"ECHO:$line\"; done"
        ));

        final String sessionId = repository.start("first", null);
        final String firstOutput = repository.waitForOutput(sessionId, 3_000);
        repository.send(sessionId, "second", null);
        final String secondOutput = repository.waitForOutput(sessionId, 3_000);

        assertThat(firstOutput).contains("READY").contains("ECHO:first");
        assertThat(secondOutput).contains("ECHO:second");
        assertThat(repository.isAlive(sessionId)).isTrue();
        repository.close(sessionId);
    }

    @Test
    void givenMultipleSends_whenRead_thenReturnsOutputsInSameSession() {
        final CodexCliSessionRepository repository = this.repository(List.of(
                "bash", "--noprofile", "--norc", "-c", "echo \"PID:$$\"; while IFS= read -r line; do echo \"PID:$$:$line\"; done"
        ));
        final String sessionId = repository.start("step-1", null);
        final String first = repository.waitForOutput(sessionId, 3_000);
        repository.send(sessionId, "step-2", null);
        final String second = repository.waitForOutput(sessionId, 3_000);

        final String pid = first.lines()
                .filter(line -> line.startsWith("PID:"))
                .findFirst()
                .orElseThrow();
        final String pidValue = pid.substring("PID:".length());
        assertThat(first).contains("PID:" + pidValue + ":step-1");
        assertThat(second).contains("PID:" + pidValue + ":step-2");
        repository.close(sessionId);
    }

    @Test
    void givenSilentProcess_whenWaitForOutput_thenTimeout() {
        final CodexCliSessionRepository repository = this.repository(List.of(
                "bash", "--noprofile", "--norc", "-c", "while IFS= read -r _; do :; done"
        ));
        final String sessionId = repository.start("first", null);

        assertThatThrownBy(() -> repository.waitForOutput(sessionId, 300))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Timed out waiting for Codex session output");
        repository.close(sessionId);
    }

    @Test
    void givenProcessExit_whenWaitForOutput_thenFailsClearly() {
        final CodexCliSessionRepository repository = this.repository(List.of(
                "bash", "--noprofile", "--norc", "-c", "exit 0"
        ));
        final String sessionId = repository.start("first", null);

        assertThatThrownBy(() -> repository.waitForOutput(sessionId, 1_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("process exited before output");
        assertThat(repository.isAlive(sessionId)).isFalse();
        repository.close(sessionId);
    }

    @Test
    void givenClosedSession_whenCloseAgain_thenIdempotent() {
        final CodexCliSessionRepository repository = this.repository(List.of(
                "bash", "--noprofile", "--norc", "-c", "while IFS= read -r _; do :; done"
        ));
        final String sessionId = repository.start("first", null);
        repository.close(sessionId);
        repository.close(sessionId);
        assertThat(repository.isAlive(sessionId)).isFalse();
    }

    private CodexCliSessionRepository repository(final List<String> command) {
        return new CodexCliSessionRepository(command, Duration.ofSeconds(1));
    }
}
