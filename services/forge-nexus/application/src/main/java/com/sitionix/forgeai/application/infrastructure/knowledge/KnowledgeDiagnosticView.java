package com.sitionix.forgeai.application.infrastructure.knowledge;

import java.util.List;

public record KnowledgeDiagnosticView(
        String code,
        String message,
        String sourceId,
        String relativePath,
        Integer attempt,
        String rawPreview,
        Integer count,
        List<String> examples
) {
}
