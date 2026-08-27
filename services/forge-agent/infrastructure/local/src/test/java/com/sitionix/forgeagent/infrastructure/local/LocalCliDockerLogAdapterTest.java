package com.sitionix.forgeagent.infrastructure.local;

import static org.assertj.core.api.Assertions.*;

import com.sitionix.forgeagent.domain.model.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class LocalCliDockerLogAdapterTest {
  @Test
  void validatesThroughTypedDockerInspectArguments() {
    var executor = new FakeExecutor(List.of());
    new LocalCliDockerLogAdapter(executor).validate("mission", null, null, null);
    assertThat(executor.command).containsExactly("docker", "container", "inspect", "--", "mission");
  }

  @Test
  void closeTerminatesFollowProcess() throws Exception {
    Process process = new ProcessBuilder("sh", "-c", "sleep 30").start();
    new ProcessLogStream(process).close();
    assertThat(process.isAlive()).isFalse();
  }

  @Test
  void nonZeroStreamExitIsObservable() throws Exception {
    Process process = new ProcessBuilder("sh", "-c", "echo denied >&2; exit 7").start();
    try (var stream = new ProcessLogStream(process)) {
      assertThat(stream.reader().readLine()).isEqualTo("denied");
      var result = stream.awaitCompletion();
      assertThat(result.exitCode()).isEqualTo(7);
      assertThat(result.errorOutput()).contains("denied");
    }
  }

  @Test
  void composeDiscoveryUsesResolvedConfigAndReturnsTheFile(@TempDir Path repository)
      throws Exception {
    Files.writeString(repository.resolve("compose.yaml"), "services: {}\n");
    var executor = new FakeExecutor(List.of("web", "worker"));
    var result = new LocalCliDockerLogAdapter(executor).discoverComposeServices(repository, null);
    assertThat(executor.command)
        .containsExactly(
            "docker",
            "compose",
            "-f",
            repository.resolve("compose.yaml").toString(),
            "config",
            "--services");
    assertThat(result).extracting(LogTargetCandidate::id).containsExactly("web", "worker");
    assertThat(result)
        .allMatch(c -> repository.resolve("compose.yaml").toString().equals(c.composeFile()));
  }

  static final class FakeExecutor extends TypedProcessExecutor {
    private final List<String> result;
    private List<String> command;

    FakeExecutor(List<String> result) {
      this.result = result;
    }

    @Override
    List<String> output(List<String> command, Path cwd) {
      this.command = command;
      return result;
    }
  }
}
