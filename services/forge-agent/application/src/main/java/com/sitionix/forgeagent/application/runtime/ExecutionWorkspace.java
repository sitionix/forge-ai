package com.sitionix.forgeagent.application.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record ExecutionWorkspace(Path cwd, List<Path> workspaceRoots) {

    public ExecutionWorkspace {
        cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        workspaceRoots = Objects.requireNonNull(workspaceRoots, "workspaceRoots").stream()
                .map(path -> Objects.requireNonNull(path, "workspaceRoot"))
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
    }
}
