package com.sitionix.forgeagent.domain.model;

import java.time.Instant;
import java.util.List;

public record ServiceProcessMetricsSnapshot(String unit, ProcessMetricsSort sort, Instant sampledAt,
                                            List<ServiceProcessMetrics> processes) {}
