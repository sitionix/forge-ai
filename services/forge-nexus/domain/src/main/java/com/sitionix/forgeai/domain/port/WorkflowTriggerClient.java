package com.sitionix.forgeai.domain.port;
import com.sitionix.forgeai.domain.model.trigger.TriggerModels;
import java.util.*;
public interface WorkflowTriggerClient {
 List<TriggerModels.Connection> connections(UUID projectId);
 List<TriggerModels.Configuration> configurations(UUID projectId,UUID connectionId);
 TriggerModels.Binding binding(UUID workflowId,UUID nodeId);
 TriggerModels.Binding save(UUID workflowId,UUID nodeId,TriggerModels.Selection selection);

 List<TriggerModels.Configuration> configurationList(UUID connectionId);
 TriggerModels.Document configuration(UUID connectionId,UUID id);
 TriggerModels.Document createConfiguration(UUID connectionId,TriggerModels.DefinitionRequest request);
 TriggerModels.Document updateConfiguration(UUID connectionId,UUID id,TriggerModels.DefinitionRequest request);
}
