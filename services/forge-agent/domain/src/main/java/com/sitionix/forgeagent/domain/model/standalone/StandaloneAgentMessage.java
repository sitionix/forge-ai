package com.sitionix.forgeagent.domain.model.standalone;
import java.time.Instant;
import java.util.UUID;
public record StandaloneAgentMessage(UUID id,UUID sessionId,UUID turnId,long sequence,String role,String text,Instant createdAt) { }
