package com.sitionix.forgeagent.infrastructure.local.mcp.gateway;

import com.sitionix.forgeagent.domain.model.McpRuntimeGrant;
import com.sitionix.forgeagent.domain.model.McpRuntimeGrantHandle;
import com.sitionix.forgeagent.domain.port.McpRuntimeGrantRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class InMemoryMcpRuntimeGrantRepository implements McpRuntimeGrantRepository {
    private final Clock clock;
    private final LongSupplier ticker;
    private final int capacity;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> grants = new HashMap<>();

    public InMemoryMcpRuntimeGrantRepository(Clock clock, LongSupplier ticker, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Grant capacity must be positive");
        this.clock = clock;
        this.ticker = ticker;
        this.capacity = capacity;
    }

    @Override public synchronized McpRuntimeGrantHandle issue(McpRuntimeGrant grant) {
        long remaining = Duration.between(clock.instant(), grant.deadline()).toNanos();
        if (remaining <= 0) throw new IllegalArgumentException("Grant deadline has passed");
        removeExpired();
        if (grants.size() >= capacity) throw new IllegalStateException("MCP runtime grant capacity reached");
        byte[] bytes = new byte[32];
        String token;
        String hash;
        do {
            random.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            hash = hash(token);
        } while (grants.containsKey(hash));
        grants.put(hash, new Entry(grant, ticker.getAsLong() + remaining));
        return new McpRuntimeGrantHandle(grant.id(), token);
    }

    @Override public synchronized Optional<McpRuntimeGrant> resolve(String token, UUID connectionId) {
        if (token == null || connectionId == null) return Optional.empty();
        String hash = hash(token);
        Entry entry = grants.get(hash);
        if (entry == null) return Optional.empty();
        if (ticker.getAsLong() - entry.expiresAtNanos() >= 0 || !clock.instant().isBefore(entry.grant().deadline())) {
            grants.remove(hash);
            return Optional.empty();
        }
        return entry.grant().connectionId().equals(connectionId) ? Optional.of(entry.grant()) : Optional.empty();
    }

    @Override public synchronized void revokeConnection(UUID connectionId) {
        grants.values().removeIf(entry -> entry.grant().connectionId().equals(connectionId));
    }

    @Override public synchronized void revokeExecution(UUID turnId) {
        grants.values().removeIf(entry -> entry.grant().turnId().equals(turnId));
    }

    @Override public synchronized void remove(UUID grantId) {
        grants.values().removeIf(entry -> entry.grant().id().equals(grantId));
    }

    Set<String> storedTokenHashes() { return Set.copyOf(grants.keySet()); }

    private void removeExpired() {
        long now = ticker.getAsLong();
        grants.values().removeIf(entry -> now - entry.expiresAtNanos() >= 0 || !clock.instant().isBefore(entry.grant().deadline()));
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record Entry(McpRuntimeGrant grant, long expiresAtNanos) {}
}
