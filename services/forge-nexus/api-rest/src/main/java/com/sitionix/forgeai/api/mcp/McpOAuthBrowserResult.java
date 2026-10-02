package com.sitionix.forgeai.api.mcp;

import java.net.URI;
import java.util.UUID;

/** Fixed local destination; provider code/state/errors are never echoed into browser URLs. */
final class McpOAuthBrowserResult {
    static final String CALLBACK="/api/v1/infrastructure/agents/integrations/mcp/oauth/callback";
    static URI location(UUID transactionId,UUID connectionId) {
        String query=transactionId==null ? "" : "transactionId="+transactionId+"&";
        if(connectionId!=null)query+="connectionId="+connectionId+"&";
        return URI.create("/fgaisox/operator/mcp-oauth-result.html?"+query+"result="+(connectionId==null?"failed":"connected"));
    }
    static UUID transaction(String state) {
        if(state==null || state.indexOf('.')<1)throw new IllegalArgumentException("Invalid OAuth state");
        return UUID.fromString(state.substring(0,state.indexOf('.')));
    }
}
