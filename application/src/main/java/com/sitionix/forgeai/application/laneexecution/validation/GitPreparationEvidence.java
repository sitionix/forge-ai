package com.sitionix.forgeai.application.laneexecution.validation;

public record GitPreparationEvidence(
        String repository,
        String branch,
        String baseBranch,
        String headCommit,
        Boolean clean
) {
}
