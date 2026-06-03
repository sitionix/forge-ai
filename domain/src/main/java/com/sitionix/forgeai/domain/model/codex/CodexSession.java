package com.sitionix.forgeai.domain.model.codex;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

@Builder(toBuilder = true)
public record CodexSession(
        String id,
        String threadId,
        Long processPid,
        List<String> command,
        String cwd,
        Instant startedAt,
        String codexVersion
) {
}
