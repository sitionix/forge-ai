package com.sitionix.forgeai.domain.model.operator.service;

import java.util.List;

public record OperatorServiceWorkspaceState(
        String configuredPath,
        String absolutePath,
        String repository,
        String cloneUrl,
        boolean exists,
        boolean gitRepository,
        String branch,
        String defaultBranch,
        boolean dirty,
        List<String> warnings
) {
}
