package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.model.ServiceMetricsSnapshot;
import com.sitionix.forgeagent.domain.model.ServiceResourceMetrics;
import com.sitionix.forgeagent.domain.model.SshConnection;
import com.sitionix.forgeagent.domain.port.ServiceMetricsPort;
import com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class CliServiceMetricsAdapter implements ServiceMetricsPort {
  static final String SERVICE_PROBE = """
      units="$(systemctl list-units --type=service --state=running --no-legend --plain | awk '{print $1}')"
      [ -z "$units" ] || systemctl show --property=Id,Description,CPUUsageNSec,MemoryCurrent,TasksCurrent $units
      printf '\nFORGE_SAMPLED_AT_NANOS=%s\n' "$(date +%s%N)"
      """;
  private final TypedProcessExecutor executor;

  public CliServiceMetricsAdapter(TypedProcessExecutor executor) { this.executor = executor; }

  @Override public ServiceMetricsSnapshot collect(SshConnection connection) {
    var result = new ArrayList<ServiceResourceMetrics>();
    var values = new LinkedHashMap<String, String>();
    Instant sampledAt = null;
    var command = RemoteShellCommand.ssh(connection, List.of("sh", "-c", SERVICE_PROBE));
    for (String row : executor.output(command, null, connection)) {
      if (row.startsWith("FORGE_SAMPLED_AT_NANOS=")) {
        sampledAt = instant(row.substring("FORGE_SAMPLED_AT_NANOS=".length()));
        continue;
      }
      if (row.isBlank()) { add(values, result); values.clear(); continue; }
      int separator = row.indexOf('=');
      if (separator > 0) values.put(row.substring(0, separator), row.substring(separator + 1));
    }
    add(values, result);
    if (sampledAt == null) throw new InfrastructureExecutionException(
        "SERVICE_METRICS_TIMESTAMP_UNAVAILABLE", "Remote service metrics timestamp is unavailable");
    return new ServiceMetricsSnapshot(sampledAt, List.copyOf(result));
  }

  private void add(Map<String, String> values, List<ServiceResourceMetrics> result) {
    String unit = values.get("Id");
    if (unit == null || !unit.endsWith(".service")) return;
    result.add(new ServiceResourceMetrics(unit, nullableText(values.get("Description")),
        number(values.get("CPUUsageNSec")), number(values.get("MemoryCurrent")),
        number(values.get("TasksCurrent"))));
  }

  private Long number(String value) {
    try { return value == null || value.isBlank() ? null : Long.valueOf(value); }
    catch (NumberFormatException ignored) { return null; }
  }
  private Instant instant(String value) {
    try {
      long nanos = Long.parseLong(value);
      return Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L);
    } catch (RuntimeException ignored) { return null; }
  }
  private String nullableText(String value) { return value == null || value.isBlank() ? null : value; }
}
