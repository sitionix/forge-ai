package com.sitionix.forgeagent.domain.port;

import com.sitionix.forgeagent.domain.model.ForgeCodexGenerationRequest;
import com.sitionix.forgeagent.domain.model.ForgeCodexGenerationResult;
import java.util.Map;
import java.util.UUID;

public interface ForgeCodexOperationsPort {
    record Snapshot(String serverVersion, Map<String,Object> payload) {}
    Snapshot models(String cursor, Integer limit, Boolean includeHidden);
    Snapshot usage();
    UUID submit(ForgeCodexGenerationRequest request);
    ForgeCodexGenerationResult get(UUID id);
    void cancel(UUID id);
}
