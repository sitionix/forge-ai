package com.sitionix.forgeai.domain.model.jarvis;

import java.util.List;

public record JarvisChatResponse(
        String answer,
        List<JarvisChatContextView> usedContext,
        List<JarvisChatDiagnosticView> diagnostics
) {
}
