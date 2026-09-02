package com.sitionix.forgeagent.infrastructure.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sitionix.forgeagent.domain.model.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliServiceProcessMetricsAdapterTest {
  @Test
  void calculatesCurrentCpuParsesRamAndThreadsAndExcludesUnrelatedProcesses() {
    var executor = new StubExecutor(List.of(
        "FORGE_HOST_TICKS\t1000\t2000",
        row(41, "worker one", 100L, 400L, 2048L, 7L),
        row(42, "helper", 200L, 300L, 1024L, 3L),
        "FORGE_SAMPLED_AT_NANOS\t1788343200123456789"));
    var connection = connection();

    var result = new CliServiceProcessMetricsAdapter(executor)
        .collect(connection, "alpha.service", ProcessMetricsSort.CPU);

    assertThat(result.sampledAt()).isEqualTo(Instant.parse("2026-09-02T10:00:00.123456789Z"));
    assertThat(result.processes()).containsExactly(
        new ServiceProcessMetrics(41, "worker one", 30.0, 2097152L, 7L),
        new ServiceProcessMetrics(42, "helper", 10.0, 1048576L, 3L));
    assertThat(result.processes()).extracting(ServiceProcessMetrics::pid).doesNotContain(99L);
    assertThat(executor.command).isEqualTo(RemoteShellCommand.ssh(connection,
        List.of("sh", "-c", CliServiceProcessMetricsAdapter.PROCESS_PROBE, "forge-process-probe", "alpha.service")));
    assertThat(CliServiceProcessMetricsAdapter.PROCESS_PROBE).doesNotContain("alpha.service");
    assertThat(executor.connection).isSameAs(connection);
    assertThat(CliServiceProcessMetricsAdapter.PROCESS_PROBE).contains("/stat").doesNotContain("ps %CPU");
  }

  @Test
  void returnsTopFiveForCpuAndRamWithUnavailableValuesLast() {
    var rows = new ArrayList<String>();
    rows.add("FORGE_HOST_TICKS\t0\t1000");
    for (int pid = 1; pid <= 7; pid++) rows.add(row(pid, "p" + pid, 0L, pid * 10L, (8L - pid) * 100L, (long) pid));
    rows.add(row(8, "unknown", null, null, null, null));
    rows.add("FORGE_SAMPLED_AT_NANOS\t1788343200000000000");

    var adapter = new CliServiceProcessMetricsAdapter(new StubExecutor(rows));
    assertThat(adapter.collect(connection(), "alpha.service", ProcessMetricsSort.CPU).processes())
        .extracting(ServiceProcessMetrics::pid).containsExactly(7L, 6L, 5L, 4L, 3L);
    assertThat(adapter.collect(connection(), "alpha.service", ProcessMetricsSort.RAM).processes())
        .extracting(ServiceProcessMetrics::pid).containsExactly(1L, 2L, 3L, 4L, 5L);
  }

  @Test
  void preservesUnavailableMeasurementsAndSupportsEmptyCgroup() {
    var unavailable = new CliServiceProcessMetricsAdapter(new StubExecutor(List.of(
        "FORGE_HOST_TICKS\t100\t100",
        row(12, null, null, null, null, null),
        "FORGE_SAMPLED_AT_NANOS\t1788343200000000000")))
        .collect(connection(), "alpha.service", ProcessMetricsSort.CPU);
    assertThat(unavailable.processes()).containsExactly(new ServiceProcessMetrics(12, null, null, null, null));

    var empty = new CliServiceProcessMetricsAdapter(new StubExecutor(List.of(
        "FORGE_HOST_TICKS\t1\t2", "FORGE_SAMPLED_AT_NANOS\t1788343200000000000")))
        .collect(connection(), "alpha.service", ProcessMetricsSort.CPU);
    assertThat(empty.processes()).isEmpty();
  }

  @Test
  void fixedProbeReadsOnlyRequestedCgroupAndDescendants(@TempDir java.nio.file.Path temp) throws Exception {
    var proc = temp.resolve("proc"); var cgroups = temp.resolve("cgroup"); var bin = temp.resolve("bin");
    java.nio.file.Files.createDirectories(proc); java.nio.file.Files.createDirectories(bin);
    java.nio.file.Files.createDirectories(cgroups.resolve("selected/child"));
    java.nio.file.Files.createDirectories(cgroups.resolve("unrelated"));
    java.nio.file.Files.writeString(cgroups.resolve("selected/cgroup.procs"), "41\n");
    java.nio.file.Files.writeString(cgroups.resolve("selected/child/cgroup.procs"), "42\n");
    java.nio.file.Files.writeString(cgroups.resolve("unrelated/cgroup.procs"), "99\n");
    writeProcess(proc, 41, "worker", 100, 10, 7, 2048);
    writeProcess(proc, 42, "helper", 50, 5, 3, 1024);
    writeProcess(proc, 99, "outsider", 900, 90, 2, 9999);
    java.nio.file.Files.writeString(proc.resolve("stat"), "cpu 100 100 100 100 100 100 100 100\n");
    var systemctl = bin.resolve("systemctl");
    java.nio.file.Files.writeString(systemctl, "#!/bin/sh\nprintf 'LoadState=loaded\\nActiveState=active\\nSubState=running\\nControlGroup=/selected\\n'\n");
    systemctl.toFile().setExecutable(true);
    var sleep = bin.resolve("sleep");
    java.nio.file.Files.writeString(sleep, "#!/bin/sh\n" +
        "printf 'cpu 200 200 200 200 200 200 200 200\\n' > \"$FORGE_PROC_ROOT/stat\"\n" +
        "sed -i 's/ 100 10 / 180 30 /' \"$FORGE_PROC_ROOT/41/stat\"\n" +
        "sed -i 's/ 50 5 / 70 5 /' \"$FORGE_PROC_ROOT/42/stat\"\n");
    sleep.toFile().setExecutable(true);
    var process = new ProcessBuilder("sh", "-c", CliServiceProcessMetricsAdapter.PROCESS_PROBE,
        "forge-process-probe", "alpha.service");
    process.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    process.environment().put("FORGE_PROC_ROOT", proc.toString());
    process.environment().put("FORGE_CGROUP_ROOT", cgroups.toString());
    process.environment().put("FORGE_SAMPLE_SECONDS", "0");
    var executed = process.start();

    assertThat(executed.waitFor()).isZero();
    var output = new String(executed.getInputStream().readAllBytes());
    assertThat(output).contains("FORGE_PROCESS\t41").contains("FORGE_PROCESS\t42")
        .doesNotContain("FORGE_PROCESS\t99");
  }

  @Test
  void fixedProbeUsesPythonEntrypointAsDisplayNameWithoutExposingArguments(
      @TempDir java.nio.file.Path temp) throws Exception {
    var proc = temp.resolve("proc"); var cgroups = temp.resolve("cgroup"); var bin = temp.resolve("bin");
    java.nio.file.Files.createDirectories(proc); java.nio.file.Files.createDirectories(bin);
    java.nio.file.Files.createDirectories(cgroups.resolve("selected"));
    java.nio.file.Files.writeString(cgroups.resolve("selected/cgroup.procs"), "41\n");
    writeProcess(proc, 41, "python3", 100, 10, 7, 2048);
    java.nio.file.Files.write(proc.resolve("41/cmdline"),
        "python3\0/opt/jobs/worker.py\0--token\0secret-value\0"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    java.nio.file.Files.writeString(proc.resolve("stat"), "cpu 100 100 100 100 100 100 100 100\n");
    var systemctl = bin.resolve("systemctl");
    java.nio.file.Files.writeString(systemctl,
        "#!/bin/sh\nprintf 'LoadState=loaded\\nActiveState=active\\nSubState=running\\nControlGroup=/selected\\n'\n");
    systemctl.toFile().setExecutable(true);
    var sleep = bin.resolve("sleep");
    java.nio.file.Files.writeString(sleep, "#!/bin/sh\n" +
        "printf 'cpu 200 200 200 200 200 200 200 200\\n' > \"$FORGE_PROC_ROOT/stat\"\n");
    sleep.toFile().setExecutable(true);
    var process = new ProcessBuilder("sh", "-c", CliServiceProcessMetricsAdapter.PROCESS_PROBE,
        "forge-process-probe", "alpha.service");
    process.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    process.environment().put("FORGE_PROC_ROOT", proc.toString());
    process.environment().put("FORGE_CGROUP_ROOT", cgroups.toString());
    var executed = process.start();

    assertThat(executed.waitFor()).isZero();
    var output = new String(executed.getInputStream().readAllBytes());
    var worker = Base64.getEncoder().encodeToString("worker.py".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var python = Base64.getEncoder().encodeToString("python3".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(output).contains("FORGE_PROCESS\t41\t" + worker + "\t").doesNotContain("\t" + python + "\t")
        .doesNotContain("secret-value");
  }

  @Test
  void fixedProbeReturnsEmptyForRunningServiceWithoutControlGroup(@TempDir java.nio.file.Path temp) throws Exception {
    var bin = temp.resolve("bin"); java.nio.file.Files.createDirectories(bin);
    var systemctl = bin.resolve("systemctl");
    java.nio.file.Files.writeString(systemctl, "#!/bin/sh\nprintf 'LoadState=loaded\\nActiveState=active\\nSubState=running\\nControlGroup=\\n'\n");
    systemctl.toFile().setExecutable(true);
    var process = new ProcessBuilder("sh", "-c", CliServiceProcessMetricsAdapter.PROCESS_PROBE,
        "forge-process-probe", "alpha.service");
    process.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    var executed = process.start();
    assertThat(executed.waitFor()).isZero();
    assertThat(new String(executed.getInputStream().readAllBytes()))
        .contains("FORGE_HOST_TICKS\t0\t1").doesNotContain("FORGE_PROCESS");
  }

  @Test
  void fixedProbeFailsClosedWhenSelectedCgroupHasNoReadableMembershipFile(@TempDir java.nio.file.Path temp) throws Exception {
    var bin = temp.resolve("bin"); var cgroups = temp.resolve("cgroup");
    java.nio.file.Files.createDirectories(bin); java.nio.file.Files.createDirectories(cgroups.resolve("selected"));
    var systemctl = bin.resolve("systemctl");
    java.nio.file.Files.writeString(systemctl, "#!/bin/sh\nprintf 'LoadState=loaded\\nActiveState=active\\nSubState=running\\nControlGroup=/selected\\n'\n");
    systemctl.toFile().setExecutable(true);
    var process = new ProcessBuilder("sh", "-c", CliServiceProcessMetricsAdapter.PROCESS_PROBE,
        "forge-process-probe", "alpha.service");
    process.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    process.environment().put("FORGE_CGROUP_ROOT", cgroups.toString());
    var executed = process.start();
    assertThat(executed.waitFor()).isNotZero();
  }

  @Test
  void malformedProbeFramesAndTypedExecutorFailuresPropagate() {
    assertThatThrownBy(() -> new CliServiceProcessMetricsAdapter(
        new StubExecutor(List.of("not-a-frame"))).collect(connection(), "alpha.service", ProcessMetricsSort.CPU))
        .isInstanceOf(com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException.class)
        .hasMessageContaining("invalid");
    var failure = new com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException(
        "RUNTIME_COMMAND_FAILED", "probe failed");
    var executor = new TypedProcessExecutor() {
      @Override List<String> output(List<String> command, java.nio.file.Path cwd, SshConnection ssh) {
        throw failure;
      }
    };
    assertThatThrownBy(() -> new CliServiceProcessMetricsAdapter(executor)
        .collect(connection(), "alpha.service", ProcessMetricsSort.CPU)).isSameAs(failure);
  }

  private static void writeProcess(java.nio.file.Path proc, long pid, String name, long user,
      long system, long threads, long rss) throws Exception {
    var dir = proc.resolve(String.valueOf(pid)); java.nio.file.Files.createDirectories(dir);
    java.nio.file.Files.writeString(dir.resolve("stat"), pid + " (" + name + ") S 0 0 0 0 0 0 0 0 0 0 "
        + user + " " + system + " 0 0 0 0 " + threads + " 0 0\n");
    java.nio.file.Files.writeString(dir.resolve("status"), "Name:\t" + name + "\nVmRSS:\t" + rss + " kB\n");
  }

  private static String row(long pid, String name, Long before, Long after, Long rssKiB, Long threads) {
    var encoded = name == null ? "-" : Base64.getEncoder().encodeToString(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return String.join("\t", "FORGE_PROCESS", String.valueOf(pid), encoded, value(before), value(after), value(rssKiB), value(threads));
  }

  private static String value(Long value) { return value == null ? "-" : String.valueOf(value); }
  private static SshConnection connection() {
    return new SshConnection(UUID.randomUUID(), UUID.randomUUID(), "server", "server.local", 22,
        "forge", SshAuthType.PRIVATE_KEY, "/key", null, Instant.EPOCH, Instant.EPOCH);
  }

  private static final class StubExecutor extends TypedProcessExecutor {
    private final List<String> rows;
    private List<String> command;
    private SshConnection connection;
    private StubExecutor(List<String> rows) { this.rows = rows; }
    @Override List<String> output(List<String> command, java.nio.file.Path cwd, SshConnection ssh) {
      this.command = command; this.connection = ssh; return rows;
    }
  }
}
