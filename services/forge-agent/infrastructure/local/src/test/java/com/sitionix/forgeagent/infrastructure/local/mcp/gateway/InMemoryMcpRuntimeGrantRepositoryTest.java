package com.sitionix.forgeagent.infrastructure.local.mcp.gateway;

import static org.assertj.core.api.Assertions.*;

import com.sitionix.forgeagent.domain.model.McpAllowedTool;
import com.sitionix.forgeagent.domain.model.McpAuthType;
import com.sitionix.forgeagent.domain.model.McpRuntimeGrant;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryMcpRuntimeGrantRepositoryTest {
    private final Instant now = Instant.parse("2026-09-25T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final long[] nanos = {0L};

    @Test void bearerIsUniqueScopedAndNotRetainedAsAStoreKey() {
        var store = store(10);
        var grant = grant(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var first = store.issue(grant);
        var second = store.issue(grant);

        assertThat(first.token()).isNotEqualTo(second.token());
        assertThat(store.resolve(first.token(), grant.connectionId())).contains(grant);
        assertThat(store.resolve(first.token(), UUID.randomUUID())).isEmpty();
        assertThat(store.resolve("wrong", grant.connectionId())).isEmpty();
        assertThat(store.storedTokenHashes()).doesNotContain(first.token(), second.token());
    }

    @Test void expiryRevocationAndRestartFailClosed() {
        var store = store(10);
        var grant = grant(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var issued = store.issue(grant);
        nanos[0] = Duration.ofMinutes(2).toNanos();
        assertThat(store.resolve(issued.token(), grant.connectionId())).isEmpty();

        nanos[0] = 0;
        var active = store.issue(grant);
        store.revokeConnection(grant.connectionId());
        assertThat(store.resolve(active.token(), grant.connectionId())).isEmpty();

        var next = store.issue(grant);
        store.revokeExecution(grant.turnId());
        assertThat(store.resolve(next.token(), grant.connectionId())).isEmpty();

        var afterRestart = store.issue(grant);
        assertThat(store(10).resolve(afterRestart.token(), grant.connectionId())).isEmpty();
    }

    @Test void capacityRejectsNewGrantsWithoutEvictingAnActiveGrant() {
        var store = store(1);
        var first = grant(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var issued = store.issue(first);
        assertThatThrownBy(() -> store.issue(grant(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.resolve(issued.token(), first.connectionId())).contains(first);
    }

    private InMemoryMcpRuntimeGrantRepository store(int capacity) {
        return new InMemoryMcpRuntimeGrantRepository(clock, () -> nanos[0], capacity);
    }

    private McpRuntimeGrant grant(UUID connection, UUID turn, UUID project) {
        return new McpRuntimeGrant(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), turn,
                UUID.randomUUID(), UUID.randomUUID(), project, connection, URI.create("https://mcp.example.test/mcp"),
                McpAuthType.BEARER, "credential-hash", Set.of(new McpAllowedTool("search", "sha256:" + "a".repeat(64))),
                now.plusSeconds(60));
    }
}
