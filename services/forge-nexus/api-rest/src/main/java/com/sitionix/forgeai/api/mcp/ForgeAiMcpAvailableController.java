package com.sitionix.forgeai.api.mcp;

import com.sitionix.forgeai.domain.usecase.AgentMcpAvailableUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "forge.mcp.enabled", havingValue = "true")
@RequestMapping("/api/v1/infrastructure/agents/integrations/mcp/available")
public class ForgeAiMcpAvailableController {
    private final AgentMcpAvailableUseCase useCase;

    public ForgeAiMcpAvailableController(AgentMcpAvailableUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping
    public McpAvailablePageResponse list(@RequestParam(required = false) String search,
                                 @RequestParam(required = false) String cursor,
                                 @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100 || (search != null && search.length() > 200)
                || (cursor != null && cursor.length() > 2048)) {
            throw new IllegalArgumentException("Invalid MCP catalog request");
        }
        return McpAvailablePageResponse.from(useCase.list(search, cursor, limit));
    }
}
