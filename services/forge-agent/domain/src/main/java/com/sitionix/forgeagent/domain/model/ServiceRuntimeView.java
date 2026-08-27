package com.sitionix.forgeagent.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public record ServiceRuntimeView(ServiceRuntimeStatus status, ServiceRuntimeProvider provider,
    ServiceConnectionType connection, String targetIdentity, Instant startedAt, Duration uptime,
    Map<String, String> metadata, String health) {}
