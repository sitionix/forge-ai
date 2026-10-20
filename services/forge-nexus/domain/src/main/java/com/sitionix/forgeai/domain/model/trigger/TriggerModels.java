package com.sitionix.forgeai.domain.model.trigger;
import java.util.UUID;
public final class TriggerModels {
 private TriggerModels(){}
 public record Document(UUID id,UUID connectionId,String name,java.util.Map<String,Object> definition){}
 public record DefinitionRequest(String name,java.util.Map<String,Object> definition){}

 public record Connection(UUID id,String displayName,boolean enabled){}
 public record Configuration(UUID id,UUID connectionId,String name){}
 public record Binding(UUID nodeId,UUID connectionId,UUID configurationId){}
 public record Selection(UUID connectionId,UUID configurationId){}
}
