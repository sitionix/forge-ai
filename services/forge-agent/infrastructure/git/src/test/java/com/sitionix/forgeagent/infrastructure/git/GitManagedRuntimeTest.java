package com.sitionix.forgeagent.infrastructure.git;

import static org.assertj.core.api.Assertions.*;
import com.sitionix.forgeagent.infrastructure.local.runtime.*;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GitManagedRuntimeTest {
    @Test void successAlsoStopsOwnedUnit() {
        var launcher = new FixtureLauncher(List.of("/bin/sh", "-c", "printf result"));
        var result = new DefaultGitCommandRunner(launcher).run(List.of("git", "status"), new GitCommandExecutionPolicy(Duration.ofSeconds(3)));
        assertThat(result.stdout()).isEqualTo("result");
        assertThat(launcher.stops.get()).isEqualTo(1);
        assertThat(launcher.arguments).containsExactly("git", "status");
    }
    @Test void timeoutStopsOwnedUnitWithoutCrossUidSignals() {
        var launcher = new FixtureLauncher(List.of("/bin/sleep", "30"));
        assertThatThrownBy(() -> new DefaultGitCommandRunner(launcher).run(List.of("git", "status"), new GitCommandExecutionPolicy(Duration.ofMillis(50))))
            .hasMessageContaining("timed out");
        assertThat(launcher.stops.get()).isEqualTo(1);
    }
    static final class FixtureLauncher extends RuntimeProcessLauncher {
        final List<String> fixture;
        final AtomicInteger stops = new AtomicInteger();
        List<String> arguments;
        FixtureLauncher(List<String> fixture) {
            super(new RuntimeBoundaryProperties(true, "/missing-unused-fixture-helper"));
            this.fixture = fixture;
        }
        @Override public ManagedRuntimeProcess startGit(List<String> command) throws IOException {
            arguments = command;
            Process pipe = new ProcessBuilder(fixture).start();
            return new ManagedRuntimeProcess(pipe, () -> { stops.incrementAndGet(); pipe.destroyForcibly(); });
        }
    }
}
