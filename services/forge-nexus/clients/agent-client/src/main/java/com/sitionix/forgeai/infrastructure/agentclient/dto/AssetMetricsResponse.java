package com.sitionix.forgeai.infrastructure.agentclient.dto;
import java.util.List;
public record AssetMetricsResponse(Double cpuTotalPercent,List<Double> cpuPerCorePercent,Long ramTotalBytes,Long ramUsedBytes,Double loadAverage1m,Double loadAverage5m,Double loadAverage15m,List<Disk> disks,List<Network> network,Long uptimeSeconds,List<Temperature> temperatures){public record Disk(String mount,Long totalBytes,Long usedBytes){} public record Network(String interfaceName,Long receivedBytes,Long transmittedBytes){} public record Temperature(String sensor,Double celsius){}}
