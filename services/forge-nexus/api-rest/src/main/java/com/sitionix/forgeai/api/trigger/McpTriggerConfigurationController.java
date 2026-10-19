package com.sitionix.forgeai.api.trigger;

import com.sitionix.forgeai.domain.model.trigger.TriggerModels;
import com.sitionix.forgeai.domain.usecase.ManageWorkflowTriggers;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/infrastructure/agents/integrations/mcp/connections/{connectionId}/trigger-configurations")
public class McpTriggerConfigurationController {
    private final ManageWorkflowTriggers service;
    public McpTriggerConfigurationController(ManageWorkflowTriggers service){this.service=service;}
    @GetMapping public List<TriggerModels.Configuration> list(@PathVariable UUID connectionId){return service.configurationList(connectionId);}
    @GetMapping("/{id}") public TriggerModels.Document get(@PathVariable UUID connectionId,@PathVariable UUID id){return service.configuration(connectionId,id);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public TriggerModels.Document create(@PathVariable UUID connectionId,@RequestBody TriggerModels.DefinitionRequest request){return service.createConfiguration(connectionId,request);}
    @PutMapping("/{id}") public TriggerModels.Document update(@PathVariable UUID connectionId,@PathVariable UUID id,@RequestBody TriggerModels.DefinitionRequest request){return service.updateConfiguration(connectionId,id,request);}
}
