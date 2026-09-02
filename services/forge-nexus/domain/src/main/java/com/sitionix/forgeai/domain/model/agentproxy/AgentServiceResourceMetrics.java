package com.sitionix.forgeai.domain.model.agentproxy;
public record AgentServiceResourceMetrics(String unit, String description, Long cpuUsageNanos, Long memoryBytes, Long tasks) {}
