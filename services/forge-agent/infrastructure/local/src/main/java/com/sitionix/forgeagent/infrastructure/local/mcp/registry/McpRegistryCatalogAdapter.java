package com.sitionix.forgeagent.infrastructure.local.mcp.registry;

import com.sitionix.forgeagent.domain.model.McpAvailablePage;
import com.sitionix.forgeagent.domain.model.McpAvailableServer;
import com.sitionix.forgeagent.domain.exception.McpRegistryUnavailableException;
import com.sitionix.forgeagent.domain.port.McpRegistryCatalog;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "forge.mcp.enabled", havingValue = "true")
public class McpRegistryCatalogAdapter implements McpRegistryCatalog {
    private final McpRegistryFeignClient client;

    public McpRegistryCatalogAdapter(McpRegistryFeignClient client) {
        this.client = client;
    }

    @Override
    @Cacheable("mcpRegistryPages")
    public McpAvailablePage list(String search, String cursor, int limit) {
        try {
            McpRegistryFeignClient.Page page = client.list(search, cursor, limit, "latest");
            if (page == null || page.servers() == null || page.metadata() == null) {
                throw new McpRegistryUnavailableException();
            }
            List<McpAvailableServer> servers = new ArrayList<>();
            for (McpRegistryFeignClient.Entry entry : page.servers()) {
                if (entry == null || entry.server() == null || entry.server().name() == null
                        || entry.server().name().isBlank() || entry.server().remotes() == null) {
                    continue;
                }
                for (McpRegistryFeignClient.Remote remote : entry.server().remotes()) {
                    if (remote != null && "streamable-http".equals(remote.type())
                            && remote.url() != null && httpUrl(remote.url())) {
                        servers.add(new McpAvailableServer(entry.server().name(), entry.server().title(),
                                entry.server().description(), entry.server().version(), remote.url()));
                        break;
                    }
                }
            }
            return new McpAvailablePage(List.copyOf(servers), page.metadata().nextCursor());
        } catch (feign.FeignException exception) {
            throw new McpRegistryUnavailableException(exception);
        }
    }

    private static boolean httpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
