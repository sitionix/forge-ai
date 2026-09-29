package com.sitionix.forgeagent.domain.port;
import com.sitionix.forgeagent.domain.model.*;
public interface McpOAuthClientRegistrationProvider {
    McpOAuthClientRegistration resolve(McpAuthenticationMetadata metadata,long deadlineNanos);
}
