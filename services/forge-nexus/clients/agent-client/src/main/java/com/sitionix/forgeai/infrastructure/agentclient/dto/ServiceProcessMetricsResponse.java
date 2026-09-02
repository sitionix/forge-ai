package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.time.Instant;
import java.util.List;

public record ServiceProcessMetricsResponse(String unit, String sort, Instant sampledAt,
                                            List<ServiceProcessResponse> processes) {}
