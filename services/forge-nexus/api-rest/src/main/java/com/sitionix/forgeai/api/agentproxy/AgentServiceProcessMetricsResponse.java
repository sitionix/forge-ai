package com.sitionix.forgeai.api.agentproxy;

import java.time.Instant;
import java.util.List;

public record AgentServiceProcessMetricsResponse(String unit, String sort, Instant sampledAt,
                                                 List<AgentServiceProcessMetricsItemResponse> processes) {}
