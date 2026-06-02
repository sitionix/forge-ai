package com.sitionix.forgeai.domain.model.codex;

import lombok.Builder;

@Builder(toBuilder = true)
public record CodexSession(
        String id,
        String threadId
) {
}
