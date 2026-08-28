package com.sitionix.forgeagent.api.dto;

import com.sitionix.forgeagent.domain.model.ServiceRuntimeProvider;

public record RuntimeTargetCandidateResponse(String id, ServiceRuntimeProvider provider) {}
