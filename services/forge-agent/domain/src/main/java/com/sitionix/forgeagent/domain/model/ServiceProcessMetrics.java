package com.sitionix.forgeagent.domain.model;

public record ServiceProcessMetrics(long pid, String process, Double cpuPercent, Long rssBytes,
                                    Long threads) {}
