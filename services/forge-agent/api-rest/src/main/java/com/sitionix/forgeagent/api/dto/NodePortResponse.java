package com.sitionix.forgeagent.api.dto;

import java.util.UUID;

public record NodePortResponse(
        UUID id,
        String name,
        String description,
        int order
) {
}
