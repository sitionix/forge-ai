package com.sitionix.forgeagent.domain.model;

import java.nio.file.Path;

public record ProjectRepositoryCloneAttempt(Path stagingPath, Path finalPath) {
}
