package com.sitionix.forgeagent.infrastructure.local.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One switch shared by every local execution deputy. */
@Component
public record RuntimeBoundaryProperties(boolean enabled, String helper) {
    public RuntimeBoundaryProperties(
            @Value("${forge.mcp.enabled:false}") boolean enabled,
            @Value("${forge.mcp.runtime.helper:/usr/local/libexec/forge-runtime-launcher}") String helper) {
        this.enabled = enabled;
        this.helper = helper;
    }
    public static RuntimeBoundaryProperties disabled() {
        return new RuntimeBoundaryProperties(false, "/usr/local/libexec/forge-runtime-launcher");
    }
}
