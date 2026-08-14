package com.sitionix.forgeai.api.agentproxy;

import java.util.UUID;

public record NodePortResponse(
        UUID id,
        String name,
        String description,
        int order
) {
}
