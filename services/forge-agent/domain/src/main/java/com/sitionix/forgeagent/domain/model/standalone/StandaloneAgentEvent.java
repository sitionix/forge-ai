package com.sitionix.forgeagent.domain.model.standalone;
import java.time.Instant;
import java.util.UUID;
public record StandaloneAgentEvent(UUID sessionId,UUID turnId,long sequence,String type,String status,String phase,String payload,Instant occurredAt) { }
