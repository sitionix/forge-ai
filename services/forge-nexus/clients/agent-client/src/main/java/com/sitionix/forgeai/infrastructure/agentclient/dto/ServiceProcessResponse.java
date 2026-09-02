package com.sitionix.forgeai.infrastructure.agentclient.dto;

public record ServiceProcessResponse(long pid, String process, Double cpuPercent, Long rssBytes,
                                     Long threads) {}
