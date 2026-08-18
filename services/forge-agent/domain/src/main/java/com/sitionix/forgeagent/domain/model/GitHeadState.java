package com.sitionix.forgeagent.domain.model;

public record GitHeadState(
        GitHeadType type,
        String ref,
        String commit
) {
}
