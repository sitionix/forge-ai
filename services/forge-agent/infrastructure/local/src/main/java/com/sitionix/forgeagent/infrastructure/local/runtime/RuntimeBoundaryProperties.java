package com.sitionix.forgeagent.infrastructure.local.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Location of the mandatory isolated runtime launcher. */
@Component
public record RuntimeBoundaryProperties(String helper) {
    public RuntimeBoundaryProperties(
            @Value("${forge.mcp.runtime.helper:/usr/local/libexec/forge-runtime-launcher}") String helper) {
        this.helper = helper;
    }
}
