package com.sitionix.forgeai.infrastructure.agentclient.dto;
import java.time.Instant;
import java.util.List;
public record ServiceMetricsResponse(Instant sampledAt, List<ServiceResourceMetricsResponse> services) {}
