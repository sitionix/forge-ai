package com.sitionix.forgeagent.domain.model;

public record LogTargetCandidate(
    String id,
    String label,
    LogTargetStatus status,
    String image,
    String composeProject,
    String composeService,
    String composeFile,
    boolean suggested) {}
