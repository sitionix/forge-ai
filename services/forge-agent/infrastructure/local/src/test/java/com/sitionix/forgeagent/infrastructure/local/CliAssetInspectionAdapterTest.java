package com.sitionix.forgeagent.infrastructure.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeagent.domain.model.SshAuthType;
import com.sitionix.forgeagent.domain.model.SshConnection;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CliAssetInspectionAdapterTest {

    @Test
    void calculatesCurrentTotalAndPerCoreCpuFromSampleDeltas() {
        var executor = new StubExecutor(
                List.of("cpu  100 0 100 800 0", "cpu0 50 0 50 400 0", "cpu1 50 0 50 400 0"),
                List.of("cpu  130 0 120 850 0", "cpu0 70 0 60 420 0", "cpu1 60 0 60 430 0"),
                List.of("RAM|1000|400", "UPTIME|42"));

        var metrics = new CliAssetInspectionAdapter(executor).metrics(connection());

        assertThat(metrics.cpuTotalPercent()).isEqualTo(50.0);
        assertThat(metrics.cpuPerCorePercent()).containsExactly(60.0, 40.0);
        assertThat(metrics.ramTotalBytes()).isEqualTo(1000L);
        assertThat(metrics.uptimeSeconds()).isEqualTo(42L);
    }

    @Test
    void leavesUnavailableCpuMeasurementsUnavailableInsteadOfReturningZero() {
        var executor = new StubExecutor(List.of("not cpu data"), List.of("still unavailable"), List.of());

        var metrics = new CliAssetInspectionAdapter(executor).metrics(connection());

        assertThat(metrics.cpuTotalPercent()).isNull();
        assertThat(metrics.cpuPerCorePercent()).isEmpty();
        assertThat(metrics.ramTotalBytes()).isNull();
        assertThat(metrics.uptimeSeconds()).isNull();
    }

    @Test
    void parsesIndentedProcNetDevRowsAndIgnoresLoopbackAndMalformedRows() {
        var adapter = new CliAssetInspectionAdapter(new StubExecutor());

        assertThat(adapter.networkMetric("  eth0: 123 1 2 3 4 5 6 7 456 8 9 10 11 12 13 14"))
                .isEqualTo(new com.sitionix.forgeagent.domain.model.AssetMetrics.NetworkMetric(
                        "eth0", 123L, 456L));
        assertThat(adapter.networkMetric("    lo: 9 0 0 0 0 0 0 0 10 0 0 0 0 0 0 0")).isNull();
        assertThat(adapter.networkMetric(" malformed row ")).isNull();
        assertThat(adapter.networkMetric("eth1: 123 only-two-columns")).isNull();
    }

    private static SshConnection connection() {
        return new SshConnection(UUID.randomUUID(), UUID.randomUUID(), "server", "server.local", 22,
                "forge", SshAuthType.PRIVATE_KEY, "/key", null, Instant.EPOCH, Instant.EPOCH);
    }

    private static final class StubExecutor extends TypedProcessExecutor {
        private final ArrayDeque<List<String>> outputs;

        @SafeVarargs
        private StubExecutor(List<String>... outputs) {
            this.outputs = new ArrayDeque<>(List.of(outputs));
        }

        @Override
        List<String> output(List<String> command, java.nio.file.Path cwd, SshConnection ssh) {
            return outputs.removeFirst();
        }
    }
}
