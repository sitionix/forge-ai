package com.sitionix.forgeagent.infrastructure.codex;

import com.sitionix.forgeagent.infrastructure.local.runtime.*;
import java.io.IOException;
import java.nio.file.Path;

/** Synthetic/live opt-in protocol fixture only; never normal-runtime isolation evidence. */
final class CodexFixtureProcesses {
    static RuntimeProcessLauncher launcher(CodexAppServerProperties properties) {
        return new RuntimeProcessLauncher(new RuntimeBoundaryProperties("/unused-fixture-helper")) {
            @Override public ManagedRuntimeProcess startCodex(Path directory) throws IOException {
                Process process = new ProcessBuilder(properties.getCommand()).directory(directory.toFile()).start();
                return new ManagedRuntimeProcess(process, () -> {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                });
            }
        };
    }
}
