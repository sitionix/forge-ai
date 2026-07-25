package com.sitionix.forgeai.infrastructure.codexcli.adapter.appserver;

import java.time.Instant;
import java.util.List;

record StartedCodexAppServer(
        Process process,
        List<String> command,
        String codexVersion,
        Instant startedAt
) {
}
