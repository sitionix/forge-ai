package com.sitionix.forgeai.domain.model.codex;

import java.util.List;

public record CodexLaneWorkspace(
        String cwd,
        List<String> runtimeWorkspaceRoots
) {
}
