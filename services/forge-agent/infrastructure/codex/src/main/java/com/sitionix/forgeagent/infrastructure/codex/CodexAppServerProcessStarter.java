package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.domain.model.McpRuntimeLaunchGrants;
import java.nio.file.Path;

interface CodexAppServerProcessStarter {

    StartedCodexAppServer start(Path workingDirectory);

    default StartedCodexAppServer start(Path workingDirectory, McpRuntimeLaunchGrants grants) {
        if (!grants.isEmpty()) throw new CodexTransportException("Isolated Codex runtime is unavailable");
        return start(workingDirectory);
    }
}
