package com.sitionix.forgeai.infrastructure.agentclient;

import com.sitionix.forgeai.infrastructure.agentclient.dto.McpAvailablePageInbound;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "mcpAvailableAgent", configuration = McpAvailableFeignConfiguration.class)
public interface McpAvailableAgentFeignClient {
    @GetMapping("/api/v1/integrations/mcp/available")
    McpAvailablePageInbound list(@RequestParam(required = false) String search,
                                 @RequestParam(required = false) String cursor,
                                 @RequestParam int limit);
}
