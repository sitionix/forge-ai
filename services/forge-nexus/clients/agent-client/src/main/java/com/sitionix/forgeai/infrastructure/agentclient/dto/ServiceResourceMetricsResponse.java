package com.sitionix.forgeai.infrastructure.agentclient.dto;
public record ServiceResourceMetricsResponse(String unit, String description, Long cpuUsageNanos, Long memoryBytes, Long tasks) {}
