package com.sitionix.forgeai.api.agentproxy;
public record AgentServiceResourceMetricsResponse(String unit, String description, Long cpuUsageNanos, Long memoryBytes, Long tasks) {}
