package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.List;

public record ServiceMetricsSnapshot(Instant sampledAt, List<ServiceResourceMetrics> services) {}
