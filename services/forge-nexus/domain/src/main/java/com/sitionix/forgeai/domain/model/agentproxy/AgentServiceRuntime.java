package com.sitionix.forgeai.domain.model.agentproxy;
import java.time.*; import java.util.Map;
public record AgentServiceRuntime(String status,String provider,String connection,String targetIdentity,Instant startedAt,Duration uptime,Map<String,String> metadata,String health) {}
