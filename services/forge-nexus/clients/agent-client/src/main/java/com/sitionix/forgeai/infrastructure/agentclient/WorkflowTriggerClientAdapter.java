package com.sitionix.forgeai.infrastructure.agentclient;
import com.sitionix.forgeai.domain.model.trigger.TriggerModels;
import com.sitionix.forgeai.domain.port.WorkflowTriggerClient;
import com.sitionix.forgeai.infrastructure.agentclient.dto.TriggerClientDtos;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class WorkflowTriggerClientAdapter implements WorkflowTriggerClient {
 private final ForgeAgentHttpClient http;private final ForgeAgentClientCallExecutor executor;
 public WorkflowTriggerClientAdapter(ForgeAgentHttpClient http,ForgeAgentClientCallExecutor executor){this.http=http;this.executor=executor;}
 public List<TriggerModels.Connection> connections(UUID projectId){return executor.execute(()->http.triggerConnections(projectId)).stream().map(TriggerClientDtos.Connection::toDomain).toList();}
 public List<TriggerModels.Configuration> configurations(UUID projectId,UUID connectionId){return executor.execute(()->http.triggerConfigurations(projectId,connectionId)).stream().map(TriggerClientDtos.Configuration::toDomain).toList();}
 public TriggerModels.Binding binding(UUID workflowId,UUID nodeId){return executor.execute(()->http.triggerBinding(workflowId,nodeId)).toDomain();}
 public TriggerModels.Binding save(UUID workflowId,UUID nodeId,TriggerModels.Selection selection){
  return executor.execute(()->http.saveTriggerBinding(workflowId,nodeId,new TriggerClientDtos.Selection(selection.connectionId(),selection.configurationId()))).toDomain();
 }
}
