package com.sitionix.forgeai.domain.model.agentproxy;

import java.time.Instant;
import java.util.List;

public record AgentServiceProcessMetricsSnapshot(String unit, String sort, Instant sampledAt,
                                                 List<AgentServiceProcessMetrics> processes) {}
