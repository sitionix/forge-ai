package com.sitionix.forgeai.api.standalone;
import java.time.Instant;
import java.util.*;
public final class StandaloneSessionDtos {
 private StandaloneSessionDtos() { }
 public record Create(UUID requestId,String agentSource,String agentId,List<UUID> repositoryIds,String text,String definitionFingerprint) { }
 public record PreviewRequest(String agentSource,String agentId,List<UUID> repositoryIds) { }
 public record Send(UUID requestId,long expectedRevision,String text) { }
 public record Cancel(UUID requestId,long expectedRevision) { }
 public record Receipt(UUID sessionId,UUID turnId,long revision,boolean accepted) { }
 public record Preview(String agentSource,String agentId,String agentName,String providerId,String modelId,String effortId,String definitionFingerprint,boolean launchable,String code,int messageLimit) { }
 public record Session(UUID id,UUID projectId,String agentSource,String agentId,String agentName,String providerId,String modelId,String effortId,List<UUID> repositoryIds,String providerConversationId,String status,long revision,UUID activeTurnId,String failureCode,Instant createdAt,Instant lastActivityAt,int messageLimit) { }
 public record Message(UUID id,UUID turnId,long sequence,String role,String text,Instant createdAt) { }
 public record Event(UUID turnId,long sequence,String type,String status,String phase,String payload,Instant occurredAt) { }
 public record MessagePage(List<Message> items,long nextSequence,boolean hasMore) { }
 public record EventPage(Session session,List<Event> items,long nextSequence,boolean hasMore) { }
 public record SessionPage(List<Session> items,String nextCursor,boolean hasMore) { }
 public record Details(Session session,MessagePage messages) { }
}
