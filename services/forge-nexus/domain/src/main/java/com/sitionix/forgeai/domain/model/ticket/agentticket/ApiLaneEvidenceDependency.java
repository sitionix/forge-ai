package com.sitionix.forgeai.domain.model.ticket.agentticket;

import lombok.Builder;

@Builder
public record ApiLaneEvidenceDependency(
        String scope,
        String role,
        Long runId
) {
}
