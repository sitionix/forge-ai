package com.sitionix.forgeagent.domain.model;

import java.util.UUID;

/** Returned only to the trusted execution setup path. */
public record McpRuntimeGrantHandle(UUID grantId, String token) {
    @Override public String toString() { return "McpRuntimeGrantHandle[grantId=" + grantId + ", token=[REDACTED]]"; }
}
