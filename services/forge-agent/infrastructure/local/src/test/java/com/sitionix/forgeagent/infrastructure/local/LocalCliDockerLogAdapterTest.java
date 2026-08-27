package com.sitionix.forgeagent.infrastructure.local;

import static org.assertj.core.api.Assertions.*;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.RuntimeTargetDiscoveryPort;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class LocalCliDockerLogAdapterTest {
  @Test
  void discoveryMapsRuntimeOutputToTypedCandidates() {
    var executor =
        new FakeExecutor(List.of("abc\tmission\tUp 2 minutes\timage:1\tancestor\tmission"));
    var result = new LocalCliDockerLogAdapter(executor, new CliRuntimeTargetDiscoveryAdapter(executor)).discover(null);
    assertThat(result)
        .containsExactly(
            new LogTargetCandidate(
                "mission",
                "mission",
                LogTargetStatus.RUNNING,
                "image:1",
                "ancestor",
                "mission",
                null,
                false));
  }

  @Test
  void discoveryUsesContainerNameInsteadOfEphemeralContainerId() {
    var first =
        new LocalCliDockerLogAdapter(
                new FakeExecutor(List.of("old-id\tmission\tUp 1 minute\timage:1\t\t")),
                new CliRuntimeTargetDiscoveryAdapter(new FakeExecutor(List.of("old-id\tmission\tUp 1 minute\timage:1\t\t"))))
            .discover(null);
    var recreated =
        new LocalCliDockerLogAdapter(
                new FakeExecutor(List.of("new-id\tmission\tUp 1 second\timage:1\t\t")),
                new CliRuntimeTargetDiscoveryAdapter(new FakeExecutor(List.of("new-id\tmission\tUp 1 second\timage:1\t\t"))))
            .discover(null);

    assertThat(first.getFirst().id()).isEqualTo("mission");
    assertThat(recreated.getFirst().id()).isEqualTo("mission");
  }

  @Test
  void validatesThroughTypedDockerInspectArguments() {
    var executor = new FakeExecutor(List.of());
    new LocalCliDockerLogAdapter(executor, targetDiscovery()).validate("mission", null, null, null);
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
    var result = new LocalCliDockerLogAdapter(executor, targetDiscovery()).discoverComposeServices(repository, null);
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

  private RuntimeTargetDiscoveryPort targetDiscovery() {
    return (connection, provider) -> List.of();
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
