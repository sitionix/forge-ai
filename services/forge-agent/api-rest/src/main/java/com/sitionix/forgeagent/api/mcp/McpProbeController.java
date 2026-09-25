package com.sitionix.forgeagent.api.mcp;

import com.sitionix.forgeagent.application.mcp.McpProbeService;
import com.sitionix.forgeagent.domain.model.McpAllowedTool;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "forge.mcp", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/integrations/mcp/connections")
public class McpProbeController {
    private final McpProbeService service;
    public McpProbeController(McpProbeService service) { this.service = service; }
    @PostMapping("/{id}/test") public McpProbeResponse test(@PathVariable UUID id) { return McpProbeResponse.from(service.test(id)); }
    @GetMapping("/{id}/tools") public List<McpProbeResponse.Tool> inventory(@PathVariable UUID id) {
        return McpProbeResponse.tools(service.inventory(id));
    }
    @PutMapping("/{id}/allowed-tools") public McpConnectionResponse approve(@PathVariable UUID id, @RequestBody Approvals request) {
        if (request == null || request.tools() == null || request.tools().stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Invalid MCP tool approval");
        return McpConnectionResponse.from(service.approve(id, request.tools().stream()
                .map(tool -> new McpAllowedTool(tool.name(), tool.schemaFingerprint()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet())));
    }
    public record Approvals(Set<Tool> tools) {}
    public record Tool(String name, String schemaFingerprint) {}
}
