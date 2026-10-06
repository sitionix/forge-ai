package com.sitionix.forgeagent.domain.model;

import java.util.List;
import java.util.Map;

public record ForgeCodexGenerationResult(String status, String rawText, String threadId, String turnId,
        String serverVersion, Map<String,Object> tokenUsage, List<Object> warnings,
        Map<String,Object> modelMetadata, String errorCode) {
    public static ForgeCodexGenerationResult state(String status, String code) {
        return new ForgeCodexGenerationResult(status,null,null,null,null,null,List.of(),Map.of(),code);
    }
}
