package com.sitionix.forgeagent.domain.model;

import java.net.URI;
import java.util.Set;

public record McpAuthenticationMetadata(boolean oauthRequired, URI resource, URI issuer,
        URI authorizationEndpoint, URI tokenEndpoint, URI revocationEndpoint, URI registrationEndpoint,
        boolean clientIdMetadataSupported, Set<String> scopes, Set<String> clientAuthenticationMethods,
        Set<String> codeChallengeMethods) {
    public McpAuthenticationMetadata {
        scopes=Set.copyOf(scopes);clientAuthenticationMethods=Set.copyOf(clientAuthenticationMethods);
        codeChallengeMethods=Set.copyOf(codeChallengeMethods);
    }
    public static McpAuthenticationMetadata noAuth(URI resource) {
        return new McpAuthenticationMetadata(false,resource,null,null,null,null,null,false,Set.of(),Set.of(),Set.of());
    }
}
