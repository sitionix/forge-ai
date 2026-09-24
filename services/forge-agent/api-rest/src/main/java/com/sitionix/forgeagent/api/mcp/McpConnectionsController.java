package com.sitionix.forgeagent.api.mcp;

import com.sitionix.forgeagent.application.mcp.McpConnectionService;
import com.sitionix.forgeagent.domain.model.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(prefix="forge.mcp",name="enabled",havingValue="true")
@RequestMapping("/api/v1/integrations/mcp/connections")
public class McpConnectionsController {
    private final McpConnectionService service;
    public McpConnectionsController(McpConnectionService service) { this.service=service; }
    @GetMapping public List<McpConnectionResponse> list() { return service.list().stream().map(McpConnectionResponse::from).toList(); }
    @GetMapping("/{id}") public McpConnectionResponse get(@PathVariable UUID id) { return McpConnectionResponse.from(service.get(id)); }
    @PostMapping public ResponseEntity<McpConnectionResponse> create(@RequestBody McpConnectionRequest request) {
        validate(request,false);
        return ResponseEntity.status(HttpStatus.CREATED).body(McpConnectionResponse.from(service.create(request.displayName(),request.endpoint(),auth(request),access(request),secret(request))));
    }
    @PutMapping("/{id}") public McpConnectionResponse update(@PathVariable UUID id,@RequestBody McpConnectionRequest request) {
        validate(request,true);
        return McpConnectionResponse.from(service.update(id,request.displayName(),request.endpoint(),auth(request),access(request),
                McpCredentialChange.valueOf(request.credentialChange().name()),secret(request)));
    }
    @PutMapping("/{id}/enabled") public McpConnectionResponse enabled(@PathVariable UUID id,@RequestBody Enabled request) {
        if (request==null || request.enabled()==null) throw new IllegalArgumentException("Invalid MCP request");
        return McpConnectionResponse.from(service.setEnabled(id,request.enabled()));
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID id) { service.remove(id); }
    @PostMapping("/{id}/reencrypt") @ResponseStatus(HttpStatus.NO_CONTENT) public void reencrypt(@PathVariable UUID id) { service.reencrypt(id); }
    public record Enabled(Boolean enabled) {}
    private static void validate(McpConnectionRequest request,boolean update) {
        if (request==null || request.transport()!=McpConnectionRequest.Transport.STREAMABLE_HTTP || request.authType()==null
                || request.projectAccess()==null || request.projectAccess().scope()==null || request.projectAccess().projectIds()==null
                || request.allowedTools()==null || !request.allowedTools().isEmpty()
                || (update && request.credentialChange()==null)
                || (!update && request.credentialChange()!=null && request.credentialChange()!=McpConnectionRequest.CredentialChange.REPLACE))
            throw new IllegalArgumentException("Invalid MCP request");
        if ((request.credentialChange()==McpConnectionRequest.CredentialChange.REPLACE)!=(request.credential()!=null))
            throw new IllegalArgumentException("Invalid MCP request");
    }
    private static McpAuthType auth(McpConnectionRequest request) { return McpAuthType.valueOf(request.authType().name()); }
    private static McpProjectAccess access(McpConnectionRequest request) {
        return new McpProjectAccess(McpProjectAccess.Scope.valueOf(request.projectAccess().scope().name()),request.projectAccess().projectIds());
    }
    private static McpCredentialSecret secret(McpConnectionRequest request) {
        if (request.credential()==null) return null;
        return switch (request.authType()) {
            case BEARER -> { if (request.credential().headers()!=null) throw new IllegalArgumentException("Invalid credential"); yield McpCredentialSecret.bearer(request.credential().bearer()); }
            case SECRET_HEADERS -> { if (request.credential().bearer()!=null) throw new IllegalArgumentException("Invalid credential"); yield McpCredentialSecret.headers(request.credential().headers()); }
            case NONE -> throw new IllegalArgumentException("Invalid credential");
        };
    }
}
