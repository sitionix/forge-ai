package com.sitionix.forgeagent.infrastructure.codex;

import java.time.Instant;
import java.util.List;

record StartedCodexAppServer(
        Process process,
        List<String> command,
        String codexVersion,
        Instant startedAt
) {
}
