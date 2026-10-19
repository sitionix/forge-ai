package com.sitionix.forgeai.application.agentproxy;
import com.sitionix.forgeai.domain.model.trigger.TriggerModels;
import com.sitionix.forgeai.domain.port.WorkflowTriggerClient;
import com.sitionix.forgeai.domain.usecase.ManageWorkflowTriggers;
import java.util.*;
import org.springframework.stereotype.Service;
@Service
public class WorkflowTriggerUseCase implements ManageWorkflowTriggers {
 private final WorkflowTriggerClient client;
 public WorkflowTriggerUseCase(WorkflowTriggerClient client){this.client=client;}
 public List<TriggerModels.Connection> connections(UUID projectId){return client.connections(projectId);}
 public List<TriggerModels.Configuration> configurations(UUID projectId,UUID connectionId){return client.configurations(projectId,connectionId);}
 public TriggerModels.Binding binding(UUID workflowId,UUID nodeId){return client.binding(workflowId,nodeId);}
 public TriggerModels.Binding save(UUID workflowId,UUID nodeId,TriggerModels.Selection selection){return client.save(workflowId,nodeId,selection);}

 public List<TriggerModels.Configuration> configurationList(UUID connectionId){return client.configurationList(connectionId);}
 public TriggerModels.Document configuration(UUID connectionId,UUID id){return client.configuration(connectionId,id);}
 public TriggerModels.Document createConfiguration(UUID connectionId,TriggerModels.DefinitionRequest request){return client.createConfiguration(connectionId,request);}
 public TriggerModels.Document updateConfiguration(UUID connectionId,UUID id,TriggerModels.DefinitionRequest request){return client.updateConfiguration(connectionId,id,request);}
}
