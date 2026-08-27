package com.sitionix.forgeagent.domain.model;

public record RuntimeTargetCandidate(
    String id,
    String label,
    ServiceRuntimeProvider provider,
    RuntimeTargetStatus status,
    String image,
    String composeProject,
    String composeService) {}
