package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.model.ServiceMetricsSnapshot;
import com.sitionix.forgeagent.domain.model.ServiceResourceMetrics;
import com.sitionix.forgeagent.domain.model.SshConnection;
import com.sitionix.forgeagent.domain.port.ServiceMetricsPort;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class CliServiceMetricsAdapter implements ServiceMetricsPort {
  static final String SERVICE_PROBE = """
      units="$(systemctl list-units --type=service --state=running --no-legend --plain | awk '{print $1}')"
      [ -z "$units" ] || systemctl show --property=Id,Description,CPUUsageNSec,MemoryCurrent,TasksCurrent $units
      """;
  private final TypedProcessExecutor executor;
  private final Clock clock;

  public CliServiceMetricsAdapter(TypedProcessExecutor executor, Clock clock) {
    this.executor = executor; this.clock = clock;
  }

  @Override public ServiceMetricsSnapshot collect(SshConnection connection) {
    var result = new ArrayList<ServiceResourceMetrics>();
    var values = new LinkedHashMap<String, String>();
    var command = RemoteShellCommand.ssh(connection, List.of("sh", "-c", SERVICE_PROBE));
    for (String row : executor.output(command, null, connection)) {
      if (row.isBlank()) { add(values, result); values.clear(); continue; }
      int separator = row.indexOf('=');
      if (separator > 0) values.put(row.substring(0, separator), row.substring(separator + 1));
    }
    add(values, result);
    return new ServiceMetricsSnapshot(clock.instant(), List.copyOf(result));
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
  private String nullableText(String value) { return value == null || value.isBlank() ? null : value; }
}
