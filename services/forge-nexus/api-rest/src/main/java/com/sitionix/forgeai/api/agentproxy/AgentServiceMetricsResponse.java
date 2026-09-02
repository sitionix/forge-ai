package com.sitionix.forgeai.api.agentproxy;
import java.time.Instant;
import java.util.List;
public record AgentServiceMetricsResponse(Instant sampledAt, List<AgentServiceResourceMetricsResponse> services) {}
