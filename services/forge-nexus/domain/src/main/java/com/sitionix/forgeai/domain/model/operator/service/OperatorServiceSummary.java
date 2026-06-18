package com.sitionix.forgeai.domain.model.operator.service;

import java.util.List;

public record OperatorServiceSummary(
        String serviceId,
        String label,
        String path,
        String absolutePath,
        String group,
        List<String> tags,
        String repository,
        String cloneUrl,
        boolean exists,
        boolean gitRepository,
        String branch,
        String defaultBranch,
        boolean dirty,
        String serviceRuntimeStatus,
        String serviceContainer,
        boolean cloneAvailable,
        boolean defaultAvailable,
        boolean dbRequired,
        String dbType,
        String dbKey,
        String dbRuntimeStatus,
        String dbContainer,
        List<String> warnings
) {
}
