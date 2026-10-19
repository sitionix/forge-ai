package com.sitionix.forgeai.domain.usecase;
import com.sitionix.forgeai.domain.model.trigger.TriggerModels;
import java.util.*;
public interface ManageWorkflowTriggers {
 List<TriggerModels.Connection> connections(UUID projectId);
 List<TriggerModels.Configuration> configurations(UUID projectId,UUID connectionId);
 TriggerModels.Binding binding(UUID workflowId,UUID nodeId);
 TriggerModels.Binding save(UUID workflowId,UUID nodeId,TriggerModels.Selection selection);
}
