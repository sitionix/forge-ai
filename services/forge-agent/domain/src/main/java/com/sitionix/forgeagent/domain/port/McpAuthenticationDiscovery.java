package com.sitionix.forgeagent.domain.port;
import com.sitionix.forgeagent.domain.model.McpAuthenticationMetadata;
import java.net.URI;
public interface McpAuthenticationDiscovery {
    McpAuthenticationMetadata discover(URI endpoint,long deadlineNanos);
}
