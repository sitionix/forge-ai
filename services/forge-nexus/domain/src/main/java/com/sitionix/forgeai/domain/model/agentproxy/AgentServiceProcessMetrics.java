package com.sitionix.forgeai.domain.model.agentproxy;

public record AgentServiceProcessMetrics(long pid, String process, Double cpuPercent, Long rssBytes,
                                         Long threads) {}
