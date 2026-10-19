package com.sitionix.forgeai.infrastructure.agentclient.dto;
import com.sitionix.forgeai.domain.model.trigger.TriggerModels;
import java.util.UUID;
public final class TriggerClientDtos {
 private TriggerClientDtos(){}
 public record Document(UUID id,UUID connectionId,String name,java.util.Map<String,Object> definition){
  public TriggerModels.Document toDomain(){return new TriggerModels.Document(id,connectionId,name,definition);}
 }
 public record DefinitionRequest(String name,java.util.Map<String,Object> definition){}

 public record Connection(UUID id,String displayName,boolean enabled){public TriggerModels.Connection toDomain(){return new TriggerModels.Connection(id,displayName,enabled);}}
 public record Configuration(UUID id,UUID connectionId,String name){public TriggerModels.Configuration toDomain(){return new TriggerModels.Configuration(id,connectionId,name);}}
 public record Binding(UUID nodeId,UUID connectionId,UUID configurationId){public TriggerModels.Binding toDomain(){return new TriggerModels.Binding(nodeId,connectionId,configurationId);}}
 public record Selection(UUID connectionId,UUID configurationId){}
}
