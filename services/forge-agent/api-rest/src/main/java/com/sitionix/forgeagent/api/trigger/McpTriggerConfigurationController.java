package com.sitionix.forgeagent.api.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.application.trigger.TriggerConfigurationService;
import com.sitionix.forgeagent.domain.model.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/integrations/mcp/connections/{connectionId}/trigger-configurations")
public class McpTriggerConfigurationController {
    private final TriggerConfigurationService service;
    private final ObjectMapper json;
    public McpTriggerConfigurationController(TriggerConfigurationService service,ObjectMapper json) { this.service=service; this.json=json; }
    @GetMapping public List<TriggerConfiguration> list(@PathVariable UUID connectionId) { return service.list(connectionId); }
    @GetMapping("/{id}") public Detail get(@PathVariable UUID connectionId,@PathVariable UUID id) { return detail(service.get(connectionId,id)); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Detail create(@PathVariable UUID connectionId,@RequestBody Request request) {
        return detail(service.create(connectionId,request.name(),request.definition()));
    }
    @PutMapping("/{id}") public Detail update(@PathVariable UUID connectionId,@PathVariable UUID id,@RequestBody Request request) {
        return detail(service.update(connectionId,id,request.name(),request.definition()));
    }
    private Detail detail(TriggerConfigurationDocument document) {
        try { return new Detail(document.id(),document.connectionId(),document.name(),
                document.definitionJson()==null ? null : json.readTree(document.definitionJson())); }
        catch(java.io.IOException error) { throw new IllegalStateException("Invalid saved configuration",error); }
    }
    public record Request(String name,JsonNode definition) {}
    public record Detail(UUID id,UUID connectionId,String name,JsonNode definition) {}
}
