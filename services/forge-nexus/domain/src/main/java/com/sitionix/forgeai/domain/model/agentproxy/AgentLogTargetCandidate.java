package com.sitionix.forgeai.domain.model.agentproxy;

public record AgentLogTargetCandidate(
    String id, String label, AgentLogTargetStatus status, String composeFile, boolean suggested) {}
