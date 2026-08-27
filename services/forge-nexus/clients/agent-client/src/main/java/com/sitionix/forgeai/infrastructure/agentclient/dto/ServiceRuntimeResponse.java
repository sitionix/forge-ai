package com.sitionix.forgeai.infrastructure.agentclient.dto;
import java.time.*; import java.util.Map;
public record ServiceRuntimeResponse(String status,String provider,String connection,String targetIdentity,Instant startedAt,Duration uptime,Map<String,String> metadata,String health) {}
