package com.sitionix.forgeagent.infrastructure.local.mcp.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

public interface McpRegistryHttpClient {
    @GetExchange("/v0.1/servers")
    Page list(@RequestParam(name = "search", required = false) String search,
              @RequestParam(name = "cursor", required = false) String cursor,
              @RequestParam(name = "limit") int limit,
              @RequestParam(name = "version") String version);

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
