package com.sitionix.forgeagent.infrastructure.local.mcp.registry;

import com.sitionix.forgeagent.domain.model.McpAvailablePage;
import com.sitionix.forgeagent.domain.model.McpAvailableServer;
import com.sitionix.forgeagent.domain.exception.McpRegistryUnavailableException;
import com.sitionix.forgeagent.domain.port.McpRegistryCatalog;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

@Component

public class McpRegistryCatalogAdapter implements McpRegistryCatalog {
    private static final Pattern REMOTE_URL_TEMPLATE = Pattern.compile(
            "^(?:https?://[^\\s]+|\\{[a-zA-Z_][a-zA-Z0-9_]*\\}[^\\s]*)$");
    private final McpRegistryHttpClient client;

    public McpRegistryCatalogAdapter(McpRegistryHttpClient client) {
        this.client = client;
    }

    @Override
    @Cacheable("mcpRegistryPages")
    public McpAvailablePage list(String search, String cursor, int limit) {
        try {
            McpRegistryHttpClient.Page page = client.list(search, cursor, limit, "latest");
            if (page == null || page.servers() == null) {
                throw new McpRegistryUnavailableException();
            }
            List<McpAvailableServer> servers = new ArrayList<>();
            for (McpRegistryHttpClient.Entry entry : page.servers()) {
                if (entry == null || entry.server() == null || entry.server().name() == null
                        || entry.server().name().isBlank() || entry.server().remotes() == null) {
                    continue;
                }
                for (McpRegistryHttpClient.Remote remote : entry.server().remotes()) {
                    if (remote != null && "streamable-http".equals(remote.type())
                            && remote.url() != null && httpUrl(remote.url())) {
                        servers.add(new McpAvailableServer(entry.server().name(), entry.server().title(),
                                entry.server().description(), entry.server().version(), remote.url(),
                                iconUrl(entry.server().icons())));
                        break;
                    }
                }
            }
            return new McpAvailablePage(List.copyOf(servers),
                    page.metadata() == null ? null : page.metadata().nextCursor());
        } catch (RestClientException exception) {
            throw new McpRegistryUnavailableException(exception);
        }
    }

    private static String iconUrl(List<McpRegistryHttpClient.Icon> icons) {
        if (icons == null) return null;
        for (var icon : icons) {
            if (icon == null || icon.src() == null) continue;
            try {
                URI uri = URI.create(icon.src());
                if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                        && uri.getUserInfo() == null) return icon.src();
            } catch (IllegalArgumentException invalid) {
                // Optional metadata must not reject an otherwise valid catalog entry.
            }
        }
        return null;
    }

    private static boolean httpUrl(String value) {
        if (!REMOTE_URL_TEMPLATE.matcher(value).matches()) {
            return false;
        }
        if (value.startsWith("{")) {
            return true;
        }
        int authorityStart = value.indexOf("://") + 3;
        int authorityEnd = value.length();
        for (int i = authorityStart; i < value.length(); i++) {
            if (value.charAt(i) == '/' || value.charAt(i) == '?' || value.charAt(i) == '#') {
                authorityEnd = i;
                break;
            }
        }
        String authority = value.substring(authorityStart, authorityEnd);
        if (authority.isEmpty() || authority.contains("@")) {
            return false;
        }
        if (value.contains("{")) {
            return true;
        }
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
