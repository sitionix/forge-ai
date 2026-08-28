package com.sitionix.forgeagent.domain.model;

import java.util.List;

/** Nullable measurements mean unavailable; collectors must never manufacture zero values. */
public record AssetMetrics(
    Double cpuTotalPercent,
    List<Double> cpuPerCorePercent,
    Long ramTotalBytes,
    Long ramUsedBytes,
    Double loadAverage1m,
    Double loadAverage5m,
    Double loadAverage15m,
    List<DiskMetric> disks,
    List<NetworkMetric> network,
    Long uptimeSeconds,
    List<TemperatureMetric> temperatures) {
  public record DiskMetric(String mount, Long totalBytes, Long usedBytes) {}
  public record NetworkMetric(String interfaceName, Long receivedBytes, Long transmittedBytes) {}
  public record TemperatureMetric(String sensor, Double celsius) {}
}
