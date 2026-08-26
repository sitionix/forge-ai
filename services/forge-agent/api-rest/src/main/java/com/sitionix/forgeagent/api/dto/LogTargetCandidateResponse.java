package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.LogTargetStatus;

public record LogTargetCandidateResponse(
    String id,
    String label,
    LogTargetStatus status,
    String image,
    String composeProject,
    String composeService,
    String composeFile,
    boolean suggested) {}
