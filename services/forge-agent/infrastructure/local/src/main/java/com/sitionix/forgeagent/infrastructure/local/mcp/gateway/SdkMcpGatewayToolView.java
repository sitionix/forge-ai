package com.sitionix.forgeagent.infrastructure.local.mcp.gateway;

import com.sitionix.forgeagent.domain.model.McpRuntimeGrant;
import com.sitionix.forgeagent.domain.port.McpRuntimeToolView;
import com.sitionix.forgeagent.infrastructure.local.mcp.protocol.SdkMcpRemoteClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Keeps only approved SDK tool definitions; no bearer or external credential is retained. */
public class SdkMcpGatewayToolView implements McpRuntimeToolView {
    private final SdkMcpRemoteClient remote;
    private final Clock clock;
    private final Map<UUID, Entry> views = new HashMap<>();

    public SdkMcpGatewayToolView(SdkMcpRemoteClient remote, Clock clock) {
        this.remote = remote;
        this.clock = clock;
    }

    @Override public void prepare(McpRuntimeGrant grant, byte[] credential) {
        List<McpSchema.Tool> tools = remote.discoverApprovedTools(
                grant.endpoint(), grant.authType(), credential, grant.tools());
        synchronized (views) {
            removeExpired();
            views.put(grant.id(), new Entry(grant.connectionId(), grant.turnId(), grant.deadline(), List.copyOf(tools)));
        }
    }

    public List<McpSchema.Tool> tools(UUID grantId) {
        synchronized (views) {
            removeExpired();
            Entry entry = views.get(grantId);
            return entry == null ? List.of() : entry.tools();
        }
    }

    @Override public void remove(UUID grantId) { synchronized (views) { views.remove(grantId); } }
    @Override public void revokeConnection(UUID connectionId) {
        synchronized (views) { views.values().removeIf(entry -> entry.connectionId().equals(connectionId)); }
    }
    @Override public void revokeExecution(UUID turnId) {
        synchronized (views) { views.values().removeIf(entry -> entry.turnId().equals(turnId)); }
    }

    private void removeExpired() {
        Instant now = clock.instant();
        views.values().removeIf(entry -> !now.isBefore(entry.deadline()));
    }

    private record Entry(UUID connectionId, UUID turnId, Instant deadline, List<McpSchema.Tool> tools) {}
}
