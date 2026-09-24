package com.sitionix.forgeagent.api.mcp;

import com.sitionix.forgeagent.application.mcp.McpAvailableService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "forge.mcp.enabled", havingValue = "true")
@RequestMapping("/api/v1/integrations/mcp/available")
public class McpAvailableController {
    private final McpAvailableService service;

    public McpAvailableController(McpAvailableService service) {
        this.service = service;
    }

    @GetMapping
    public McpAvailablePageResponse list(@RequestParam(required = false) String search,
                                 @RequestParam(required = false) String cursor,
                                 @RequestParam(defaultValue = "20") int limit) {
        return McpAvailablePageResponse.from(service.list(search, cursor, limit));
    }
}
