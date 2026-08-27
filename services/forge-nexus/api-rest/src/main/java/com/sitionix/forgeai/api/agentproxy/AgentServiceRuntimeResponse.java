package com.sitionix.forgeai.api.agentproxy;
import java.time.*; import java.util.Map;
public record AgentServiceRuntimeResponse(String status,String provider,String connection,String targetIdentity,Instant startedAt,Duration uptime,Map<String,String> metadata,String health) {}
