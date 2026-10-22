package com.sitionix.forgeai.infrastructure.agentclient.standalone;
import com.sitionix.forgeai.domain.model.standalone.StandaloneSessionContracts.*;
public final class StandaloneAgentSessionClientMapper {
 private StandaloneAgentSessionClientMapper() { }
 public static StandaloneSessionClientDtos.Create request(Create r){if(r==null)return null;return new StandaloneSessionClientDtos.Create(r.requestId(),r.agentSource(),r.agentId(),r.repositoryIds(),r.text(),r.definitionFingerprint());}
 public static StandaloneSessionClientDtos.Send request(Send r){if(r==null)return null;return new StandaloneSessionClientDtos.Send(r.requestId(),r.expectedRevision(),r.text());}
 public static StandaloneSessionClientDtos.Cancel request(Cancel r){if(r==null)return null;return new StandaloneSessionClientDtos.Cancel(r.requestId(),r.expectedRevision());}
 public static StandaloneSessionClientDtos.ProviderDefaults request(ProviderDefaults r){if(r==null)return null;return new StandaloneSessionClientDtos.ProviderDefaults(r.providerId(),r.modelId(),r.effortId());}
 public static Receipt map(StandaloneSessionClientDtos.Receipt r){return new Receipt(r.sessionId(),r.turnId(),r.revision(),r.accepted());}
 public static Preview map(StandaloneSessionClientDtos.Preview r){return new Preview(r.agentSource(),r.agentId(),r.agentName(),r.providerId(),r.modelId(),r.effortId(),r.definitionFingerprint(),r.launchable(),r.code(),r.messageLimit());}
 public static Session map(StandaloneSessionClientDtos.Session r){return new Session(r.id(),r.projectId(),r.agentSource(),r.agentId(),r.agentName(),r.providerId(),r.modelId(),r.effortId(),r.repositoryIds(),r.providerConversationId(),r.status(),r.revision(),r.activeTurnId(),r.failureCode(),r.createdAt(),r.lastActivityAt(),r.messageLimit());}
 public static Message map(StandaloneSessionClientDtos.Message r){return new Message(r.id(),r.turnId(),r.sequence(),r.role(),r.text(),r.createdAt());}
 public static Event map(StandaloneSessionClientDtos.Event r){return new Event(r.turnId(),r.sequence(),r.type(),r.status(),r.phase(),r.payload(),r.occurredAt());}
 public static MessagePage map(StandaloneSessionClientDtos.MessagePage r){return new MessagePage(r.items().stream().map(StandaloneAgentSessionClientMapper::map).toList(),r.nextSequence(),r.hasMore());}
 public static EventPage map(StandaloneSessionClientDtos.EventPage r){return new EventPage(map(r.session()),r.items().stream().map(StandaloneAgentSessionClientMapper::map).toList(),r.nextSequence(),r.hasMore());}
 public static SessionPage map(StandaloneSessionClientDtos.SessionPage r){return new SessionPage(r.items().stream().map(StandaloneAgentSessionClientMapper::map).toList(),r.nextCursor(),r.hasMore());}
 public static Details map(StandaloneSessionClientDtos.Details r){return new Details(map(r.session()),map(r.messages()));}
}
