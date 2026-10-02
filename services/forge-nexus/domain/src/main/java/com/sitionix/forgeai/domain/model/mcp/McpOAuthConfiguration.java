package com.sitionix.forgeai.domain.model.mcp;

import java.net.URI;
import java.util.Set;

/** Public pre-registered client metadata; credentials remain write-only. */
public record McpOAuthConfiguration(URI issuer,URI authorizationEndpoint,URI tokenEndpoint,URI revocationEndpoint,
        String clientId,String clientAuthenticationMethod,Set<String> scopes,URI resource) {}
