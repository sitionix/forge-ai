package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.UUID;

public record NodePortRequest(
        UUID id,
        String name,
        String description,
        int order
) {
}
