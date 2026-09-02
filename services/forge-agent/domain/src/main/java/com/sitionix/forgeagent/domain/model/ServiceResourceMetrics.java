package com.sitionix.forgeagent.domain.model;

public record ServiceResourceMetrics(
    String unit, String description, Long cpuUsageNanos, Long memoryBytes, Long tasks) {}
