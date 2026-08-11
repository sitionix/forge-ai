package com.sitionix.forgeagent.api.dto;

import java.util.List;

public record AiRuntimeResponse(
        List<CodexRuntimeProviderResponse> providers
) {
}
