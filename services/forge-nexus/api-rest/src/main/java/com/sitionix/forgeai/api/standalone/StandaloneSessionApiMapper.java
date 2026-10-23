package com.sitionix.forgeai.api.standalone;
import com.sitionix.forgeai.domain.model.standalone.StandaloneSessionContracts.*;
public final class StandaloneSessionApiMapper {
 private StandaloneSessionApiMapper() { }
 public static Create request(StandaloneSessionDtos.Create r){return new Create(r.requestId(),r.agentSource(),r.agentId(),r.repositoryIds(),r.text(),r.definitionFingerprint());}
 public static PreviewRequest request(StandaloneSessionDtos.PreviewRequest r){return new PreviewRequest(r.agentSource(),r.agentId(),r.repositoryIds());}
 public static Send request(StandaloneSessionDtos.Send r){return new Send(r.requestId(),r.expectedRevision(),r.text());}
 public static Cancel request(StandaloneSessionDtos.Cancel r){return new Cancel(r.requestId(),r.expectedRevision());}
 public static StandaloneSessionDtos.Receipt map(Receipt r){return new StandaloneSessionDtos.Receipt(r.sessionId(),r.turnId(),r.revision(),r.accepted());}
 public static StandaloneSessionDtos.Preview map(Preview r){return new StandaloneSessionDtos.Preview(r.agentSource(),r.agentId(),r.agentName(),r.providerId(),r.modelId(),r.effortId(),r.definitionFingerprint(),r.launchable(),r.code(),r.messageLimit());}
 public static StandaloneSessionDtos.Session map(Session r){return new StandaloneSessionDtos.Session(r.id(),r.projectId(),r.agentSource(),r.agentId(),r.agentName(),r.providerId(),r.modelId(),r.effortId(),r.repositoryIds(),r.providerConversationId(),r.status(),r.revision(),r.activeTurnId(),r.failureCode(),r.createdAt(),r.lastActivityAt(),r.messageLimit());}
 public static StandaloneSessionDtos.Message map(Message r){return new StandaloneSessionDtos.Message(r.id(),r.turnId(),r.sequence(),r.role(),r.text(),r.createdAt());}
 public static StandaloneSessionDtos.Event map(Event r){return new StandaloneSessionDtos.Event(r.turnId(),r.sequence(),r.type(),r.status(),r.phase(),r.payload(),r.occurredAt());}
 public static StandaloneSessionDtos.MessagePage map(MessagePage r){return new StandaloneSessionDtos.MessagePage(r.items().stream().map(StandaloneSessionApiMapper::map).toList(),r.nextSequence(),r.hasMore());}
 public static StandaloneSessionDtos.EventPage map(EventPage r){return new StandaloneSessionDtos.EventPage(map(r.session()),r.items().stream().map(StandaloneSessionApiMapper::map).toList(),r.nextSequence(),r.hasMore());}
 public static StandaloneSessionDtos.SessionPage map(SessionPage r){return new StandaloneSessionDtos.SessionPage(r.items().stream().map(StandaloneSessionApiMapper::map).toList(),r.nextCursor(),r.hasMore());}
 public static StandaloneSessionDtos.Details map(Details r){return new StandaloneSessionDtos.Details(map(r.session()),map(r.messages()));}
}
