package com.sitionix.forgeai.domain.model.agentproxy;

public record AgentLogTargetCandidate(
    String id,
    String label,
    AgentLogTargetStatus status,
    String image,
    String composeProject,
    String composeService,
    String composeFile,
    boolean suggested) {}
