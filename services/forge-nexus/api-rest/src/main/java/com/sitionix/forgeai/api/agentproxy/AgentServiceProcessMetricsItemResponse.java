package com.sitionix.forgeai.api.agentproxy;

public record AgentServiceProcessMetricsItemResponse(long pid, String process, Double cpuPercent,
                                                     Long rssBytes, Long threads) {}
