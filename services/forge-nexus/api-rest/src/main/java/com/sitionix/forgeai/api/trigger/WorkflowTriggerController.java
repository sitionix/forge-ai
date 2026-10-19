package com.sitionix.forgeai.api.trigger;
import com.sitionix.forgeai.domain.model.trigger.TriggerModels;
import com.sitionix.forgeai.domain.usecase.ManageWorkflowTriggers;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/infrastructure/agents")
public class WorkflowTriggerController {
 private final ManageWorkflowTriggers service;
 public WorkflowTriggerController(ManageWorkflowTriggers service){this.service=service;}
 @GetMapping("/projects/{projectId}/trigger-connections")
 public List<TriggerModels.Connection> connections(@PathVariable UUID projectId){return service.connections(projectId);}
 @GetMapping("/projects/{projectId}/trigger-connections/{connectionId}/configurations")
 public List<TriggerModels.Configuration> configurations(@PathVariable UUID projectId,@PathVariable UUID connectionId){return service.configurations(projectId,connectionId);}
 @GetMapping("/workflows/{workflowId}/nodes/{nodeId}/trigger-binding")
 public TriggerModels.Binding binding(@PathVariable UUID workflowId,@PathVariable UUID nodeId){return service.binding(workflowId,nodeId);}
 @PutMapping("/workflows/{workflowId}/nodes/{nodeId}/trigger-binding")
 public TriggerModels.Binding save(@PathVariable UUID workflowId,@PathVariable UUID nodeId,@RequestBody TriggerModels.Selection selection){return service.save(workflowId,nodeId,selection);}
}
