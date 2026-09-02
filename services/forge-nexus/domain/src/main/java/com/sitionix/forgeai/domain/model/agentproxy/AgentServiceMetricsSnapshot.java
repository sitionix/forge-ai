package com.sitionix.forgeai.domain.model.agentproxy;
import java.time.Instant;
import java.util.List;
public record AgentServiceMetricsSnapshot(Instant sampledAt, List<AgentServiceResourceMetrics> services) {}
