package com.sitionix.forgeagent.infrastructure.local.mcp;

import com.sitionix.forgeagent.domain.exception.McpProbeException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Shared outbound endpoint policy; DNS pinning is deliberately not claimed. */
public final class McpEndpointPolicy {
    private final Set<String> allowedPrivateEndpoints;
    public McpEndpointPolicy(Set<String> allowedPrivateEndpoints) { this.allowedPrivateEndpoints = Set.copyOf(allowedPrivateEndpoints); }
    public void validate(URI endpoint) {
        if (endpoint == null || endpoint.getHost() == null || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme())))
            throw new McpProbeException(McpProbeException.Reason.ENDPOINT_DENIED);
        int port = endpoint.getPort() < 0 ? ("https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80) : endpoint.getPort();
        if (port < 1 || port > 65535) throw new McpProbeException(McpProbeException.Reason.ENDPOINT_DENIED);
        try {
            boolean allowed = allowedPrivateEndpoints.contains(endpoint.getHost().toLowerCase(Locale.ROOT) + ":" + port);
            InetAddress[] addresses = InetAddress.getAllByName(endpoint.getHost());
            if (addresses.length == 0) throw new McpProbeException(McpProbeException.Reason.ENDPOINT_DENIED);
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress())
                    throw new McpProbeException(McpProbeException.Reason.ENDPOINT_DENIED);
                byte[] raw = address.getAddress();
                if (raw.length == 16 && ((raw[0] & 0xfe) == 0xfc) && !allowed)
                    throw new McpProbeException(McpProbeException.Reason.ENDPOINT_DENIED);
                if (raw.length == 4 && (raw[0] & 0xff) == 100 && ((raw[1] & 0xc0) == 64) && !allowed)
                    throw new McpProbeException(McpProbeException.Reason.ENDPOINT_DENIED);
                if ((address.isLoopbackAddress() || address.isSiteLocalAddress()) && !allowed)
                    throw new McpProbeException(McpProbeException.Reason.ENDPOINT_DENIED);
            }
        } catch (McpProbeException exception) { throw exception; }
        catch (Exception exception) { throw new McpProbeException(McpProbeException.Reason.UNAVAILABLE); }
    }
}
