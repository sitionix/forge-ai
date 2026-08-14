package com.sitionix.forgeagent.domain.model;

import java.util.UUID;

public record NodePort(
        UUID id,
        String name,
        String description,
        int order
) {
}
