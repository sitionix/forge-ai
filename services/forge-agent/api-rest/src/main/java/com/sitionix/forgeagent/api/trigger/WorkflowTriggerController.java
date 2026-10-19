package com.sitionix.forgeagent.api.trigger;
import com.sitionix.forgeagent.application.trigger.TriggerBindingService;
import com.sitionix.forgeagent.domain.model.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
public class WorkflowTriggerController {
 private final TriggerBindingService service;
 public WorkflowTriggerController(TriggerBindingService service){this.service=service;}
 @GetMapping("/api/v1/projects/{projectId}/trigger-connections")
 public List<TriggerConnection> connections(@PathVariable UUID projectId){return service.listConnections(projectId);}
 @GetMapping("/api/v1/projects/{projectId}/trigger-connections/{connectionId}/configurations")
 public List<TriggerConfiguration> configurations(@PathVariable UUID projectId,@PathVariable UUID connectionId){return service.listConfigurations(projectId,connectionId);}
 @GetMapping("/api/v1/workflows/{workflowId}/nodes/{nodeId}/trigger-binding")
 public TriggerBinding get(@PathVariable UUID workflowId,@PathVariable UUID nodeId){return service.getBinding(workflowId,nodeId);}
 @PutMapping("/api/v1/workflows/{workflowId}/nodes/{nodeId}/trigger-binding")
 public TriggerBinding save(@PathVariable UUID workflowId,@PathVariable UUID nodeId,@RequestBody Selection request){
  return service.saveBinding(workflowId,nodeId,request.connectionId(),request.configurationId());
 }
 public record Selection(UUID connectionId,UUID configurationId){}
}
