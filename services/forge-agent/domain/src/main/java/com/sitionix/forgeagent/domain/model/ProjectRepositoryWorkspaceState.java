package com.sitionix.forgeagent.domain.model;

import java.nio.file.Path;
import java.util.UUID;

public record ProjectRepositoryWorkspaceState(
        UUID repositoryId,
        Path path,
        boolean cloned
) {
}
