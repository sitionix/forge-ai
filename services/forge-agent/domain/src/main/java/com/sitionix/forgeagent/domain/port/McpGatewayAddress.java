package com.sitionix.forgeagent.domain.port;

import java.net.URI;

/** The Agent's actual loopback HTTP connector, resolved after the server starts. */
public interface McpGatewayAddress {
    URI baseUrl();
}
