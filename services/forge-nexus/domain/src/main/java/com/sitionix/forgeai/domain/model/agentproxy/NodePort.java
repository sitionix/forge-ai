package com.sitionix.forgeai.domain.model.agentproxy;

import java.util.UUID;

public record NodePort(
        UUID id,
        String name,
        String description,
        int order
) {
}
