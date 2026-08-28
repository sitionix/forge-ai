package com.sitionix.forgeai.api.agentproxy;

import java.util.List;

public record AgentAssetMetricsResponse(
        Double cpuTotalPercent,
        List<Double> cpuPerCorePercent,
        Long ramTotalBytes,
        Long ramUsedBytes,
        Double loadAverage1m,
        Double loadAverage5m,
        Double loadAverage15m,
        List<DiskResponse> disks,
        List<NetworkResponse> network,
        Long uptimeSeconds,
        List<TemperatureResponse> temperatures) {

    public record DiskResponse(String mount, Long totalBytes, Long usedBytes) {
    }

    public record NetworkResponse(String interfaceName, Long receivedBytes, Long transmittedBytes) {
    }

    public record TemperatureResponse(String sensor, Double celsius) {
    }
}
