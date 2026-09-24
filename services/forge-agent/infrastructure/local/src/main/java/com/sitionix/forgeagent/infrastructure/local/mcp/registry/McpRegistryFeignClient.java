package com.sitionix.forgeagent.infrastructure.local.mcp.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "mcpRegistry", url = "${forge.mcp.registry.base-url}")
public interface McpRegistryFeignClient {
    @GetMapping("/v0.1/servers")
    Page list(@RequestParam(required = false) String search,
              @RequestParam(required = false) String cursor,
              @RequestParam int limit,
              @RequestParam String version);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Page(List<Entry> servers, Metadata metadata) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Entry(Server server) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Server(String name, String title, String description, String version, List<Remote> remotes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Remote(String type, String url) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Metadata(String nextCursor) {}
}
