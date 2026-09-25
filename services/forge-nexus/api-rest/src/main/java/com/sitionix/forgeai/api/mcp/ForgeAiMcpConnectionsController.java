package com.sitionix.forgeai.api.mcp;

import com.sitionix.forgeai.domain.model.mcp.McpConnection;
import com.sitionix.forgeai.domain.usecase.ManageAgentMcpConnections;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(prefix="forge.mcp",name="enabled",havingValue="true")
@RequestMapping("/api/v1/infrastructure/agents/integrations/mcp/connections")
public class ForgeAiMcpConnectionsController {
    private final ManageAgentMcpConnections useCase;
    private final McpApiMapper mapper;
    public ForgeAiMcpConnectionsController(ManageAgentMcpConnections useCase,McpApiMapper mapper){this.useCase=useCase;this.mapper=mapper;}
    @GetMapping public List<McpConnectionResponse> list(){return useCase.list().stream().map(mapper::toResponse).toList();}
    @GetMapping("/{id}") public McpConnectionResponse get(@PathVariable UUID id){return mapper.toResponse(useCase.get(id));}
    @PostMapping("/{id}/test") public McpProbeResponse test(@PathVariable UUID id){return McpProbeResponse.from(useCase.test(id));}
    @GetMapping("/{id}/tools") public List<McpProbeResponse.Tool> inventory(@PathVariable UUID id){
        return useCase.inventory(id).stream().map(tool -> new McpProbeResponse.Tool(tool.name(),tool.description(),tool.schemaFingerprint())).toList();
    }
    @PutMapping("/{id}/allowed-tools") public McpConnectionResponse approve(@PathVariable UUID id,@RequestBody Approvals request){
        if(request==null || request.tools()==null)throw new IllegalArgumentException("Invalid MCP tool approval");
        return mapper.toResponse(useCase.approve(id,request.tools()));
    }
    @PostMapping public ResponseEntity<McpConnectionResponse> create(@RequestBody McpConnectionRequest request){
        if(request==null) throw new IllegalArgumentException("Invalid MCP request");
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(useCase.create(mapper.toCommand(request))));
    }
    @PutMapping("/{id}") public McpConnectionResponse update(@PathVariable UUID id,@RequestBody McpConnectionRequest request){
        if(request==null) throw new IllegalArgumentException("Invalid MCP request");
        return mapper.toResponse(useCase.update(id,mapper.toCommand(request)));
    }
    @PutMapping("/{id}/enabled") public McpConnectionResponse enabled(@PathVariable UUID id,@RequestBody Enabled request){
        if(request==null || request.enabled()==null)throw new IllegalArgumentException("Invalid MCP request");
        return mapper.toResponse(useCase.setEnabled(id,request.enabled()));
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID id){useCase.delete(id);}
    @PostMapping("/{id}/reencrypt") @ResponseStatus(HttpStatus.NO_CONTENT) public void reencrypt(@PathVariable UUID id){useCase.reencrypt(id);}
    public record Enabled(Boolean enabled){}
    public record Approvals(java.util.Set<McpConnection.AllowedTool> tools){}
}
