package com.sitionix.forgeagent.api.standalone;
import com.sitionix.forgeagent.domain.model.standalone.*;
import static com.sitionix.forgeagent.api.standalone.StandaloneSessionDtos.*;
public final class StandaloneSessionApiMapper {
 private StandaloneSessionApiMapper() { }
 public static Session map(StandaloneAgentSession s){var d=s.definition();return new Session(s.id(),s.projectId(),d.source(),d.reference(),d.name(),d.model().providerId(),d.model().modelId(),d.model().effortId(),s.repositoryIds(),s.providerConversationId(),s.status(),s.revision(),s.activeTurnId(),s.failureCode(),s.createdAt(),s.lastActivityAt(),StandaloneSessionPolicy.MAX_MESSAGE_CHARACTERS);}
 public static Message map(StandaloneAgentMessage m){return new Message(m.id(),m.turnId(),m.sequence(),m.role(),m.text(),m.createdAt());}
 public static Event map(StandaloneAgentEvent e){return new Event(e.turnId(),e.sequence(),e.type(),e.status(),e.phase(),publicActivity(e.payload()),e.occurredAt());}
 private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
 private static final java.util.Set<String> ACTIVITY_FIELDS=java.util.Set.of("command","toolName","tool","code","origin","stage","connectionId","exitCode","durationMs","senderThreadId","receiverThreadIds","agentThreadId","kind","model","agentsStates","status");
 private static String publicActivity(String raw){
  try {
   var source=JSON.readTree(raw);if(source==null||!source.isObject())throw new IllegalArgumentException();
   var safe=JSON.createObjectNode();source.fields().forEachRemaining(entry->{if(ACTIVITY_FIELDS.contains(entry.getKey()))safe.set(entry.getKey(),publicValue(entry.getValue()));});
   return JSON.writeValueAsString(safe);
  }catch(Exception unsupported){return "{\"code\":\"UNSUPPORTED_ACTIVITY_PAYLOAD\"}";}
 }
 private static com.fasterxml.jackson.databind.JsonNode publicValue(com.fasterxml.jackson.databind.JsonNode value){
  if(value.isTextual())return JSON.getNodeFactory().textNode(com.sitionix.forgeagent.domain.model.ExecutionDiagnosticText.sanitize(value.asText()).replaceAll("(?<![A-Za-z0-9:/])/(?:[^\\s\"'<>|;&]+)","[runtime-path]"));
  if(value.isArray()){var result=JSON.createArrayNode();value.forEach(v->result.add(publicValue(v)));return result;}
  if(value.isObject()){var result=JSON.createObjectNode();value.fields().forEachRemaining(e->{if("status".equals(e.getKey()))result.set(e.getKey(),publicValue(e.getValue()));else if(e.getValue().isObject())result.set(e.getKey(),publicValue(e.getValue()));});return result;}
  return value;
 }
}
