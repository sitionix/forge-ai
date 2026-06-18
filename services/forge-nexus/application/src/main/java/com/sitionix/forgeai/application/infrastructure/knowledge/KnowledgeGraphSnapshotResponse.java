package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;
import java.util.Map;

public record KnowledgeGraphSnapshotResponse(
        int statusCode,
        Map<String, Object> body,
        Map<String, List<String>> headers
) {
}
