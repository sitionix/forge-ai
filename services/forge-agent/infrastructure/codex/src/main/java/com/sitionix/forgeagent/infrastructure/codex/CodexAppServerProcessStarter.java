package com.sitionix.forgeagent.infrastructure.codex;

import java.nio.file.Path;

interface CodexAppServerProcessStarter {

    StartedCodexAppServer start(Path workingDirectory);
}
