package com.sitionix.forgeagent.api.dto;
import com.sitionix.forgeagent.domain.model.*;
import java.time.*;
import java.util.Map;
public record ServiceRuntimeResponse(ServiceRuntimeStatus status,ServiceRuntimeProvider provider,
    ServiceConnectionType connection,String targetIdentity,Instant startedAt,Duration uptime,
    Map<String,String> metadata,String health) {}
