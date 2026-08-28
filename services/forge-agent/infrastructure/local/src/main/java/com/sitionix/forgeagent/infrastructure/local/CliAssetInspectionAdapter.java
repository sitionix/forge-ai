package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.AssetInspectionPort;
import java.util.*;
import org.springframework.stereotype.Component;

/** Linux-only fixed probes. No part of a command is supplied by an API caller. */
@Component
public class CliAssetInspectionAdapter implements AssetInspectionPort {
  private static final String METRICS_PROBE = """
      awk '/^cpu[0-9 ]/{idle=$5+$6; total=0; for(i=2;i<=NF;i++) total+=$i; printf "CPU|%s|%.2f\\n",$1,(total-idle)*100/total}' /proc/stat
      free -b | awk '/^Mem:/{print "RAM|"$2"|"$3}'
      awk '{print "LOAD|"$1"|"$2"|"$3}' /proc/loadavg
      df -B1 -P | awk 'NR>1{print "DISK|"$6"|"$2"|"$3}'
      awk -F'[: ]+' 'NR>2 && $1!="lo"{print "NET|"$1"|"$3"|"$11}' /proc/net/dev
      awk '{printf "UPTIME|%.0f\\n",$1}' /proc/uptime
      for f in /sys/class/thermal/thermal_zone*/temp; do [ -r "$f" ] && awk -v n="$f" '{print "TEMP|"n"|"$1/1000}' "$f"; done
      """;

  private final TypedProcessExecutor executor;
  public CliAssetInspectionAdapter(TypedProcessExecutor executor) { this.executor = executor; }

  @Override public AssetMetrics metrics(SshConnection connection) {
    List<String> rows = output(connection, List.of("sh", "-c", METRICS_PROBE));
    Double total = null; var cores = new ArrayList<Double>(); Long ramTotal = null, ramUsed = null, uptime = null;
    Double load1 = null, load5 = null, load15 = null;
    var disks = new ArrayList<AssetMetrics.DiskMetric>();
    var network = new ArrayList<AssetMetrics.NetworkMetric>();
    var temperatures = new ArrayList<AssetMetrics.TemperatureMetric>();
    for (String row : rows) {
      String[] p = row.split("\\|", -1);
      try {
        switch (p[0]) {
          case "CPU" -> { double value = Double.parseDouble(p[2]); if ("cpu".equals(p[1])) total = value; else cores.add(value); }
          case "RAM" -> { ramTotal = Long.parseLong(p[1]); ramUsed = Long.parseLong(p[2]); }
          case "LOAD" -> { load1 = Double.parseDouble(p[1]); load5 = Double.parseDouble(p[2]); load15 = Double.parseDouble(p[3]); }
          case "DISK" -> disks.add(new AssetMetrics.DiskMetric(p[1], Long.parseLong(p[2]), Long.parseLong(p[3])));
          case "NET" -> network.add(new AssetMetrics.NetworkMetric(p[1], Long.parseLong(p[2]), Long.parseLong(p[3])));
          case "UPTIME" -> uptime = Long.parseLong(p[1]);
          case "TEMP" -> temperatures.add(new AssetMetrics.TemperatureMetric(p[1], Double.parseDouble(p[2])));
          default -> { }
        }
      } catch (RuntimeException ignored) { /* malformed/unavailable measurements stay absent */ }
    }
    return new AssetMetrics(total, List.copyOf(cores), ramTotal, ramUsed, load1, load5, load15,
        List.copyOf(disks), List.copyOf(network), uptime, List.copyOf(temperatures));
  }

  @Override public AssetCapabilities capabilities(SshConnection connection) {
    var rows = output(connection, List.of("sh", "-c",
        "command -v systemctl >/dev/null 2>&1 && echo SYSTEMD || true; command -v docker >/dev/null 2>&1 && echo DOCKER || true"));
    return new AssetCapabilities(rows.contains("SYSTEMD"), rows.contains("DOCKER"));
  }

  private List<String> output(SshConnection connection, List<String> fixed) {
    if (connection == null) throw new InfrastructureExecutionException("SSH_CONNECTION_REQUIRED", "SSH connection is required");
    return executor.output(RemoteShellCommand.ssh(connection, fixed), null, connection);
  }
}
