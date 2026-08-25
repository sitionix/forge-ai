package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.sitionix.forgeai.domain.model.agentproxy.AgentLogTargetStatus;

public record AgentLogTargetCandidateResponse(
    String id,
    String label,
    AgentLogTargetStatus status,
    String image,
    String composeProject,
    String composeService,
    String composeFile,
    boolean suggested) {}
