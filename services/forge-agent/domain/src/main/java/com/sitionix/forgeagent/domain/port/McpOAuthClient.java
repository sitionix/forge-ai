package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.*;
import java.net.URI;

public interface McpOAuthClient {
    McpOAuthAuthorization authorization(McpOAuthConfiguration configuration, URI redirectUri, String state);
    McpOAuthTokens exchange(McpOAuthConfiguration configuration, McpOAuthCredentials credentials, String code, String verifier);
    McpOAuthTokens refresh(McpOAuthConfiguration configuration, McpOAuthCredentials credentials);
    void revoke(McpOAuthConfiguration configuration, McpOAuthCredentials credentials);
}
