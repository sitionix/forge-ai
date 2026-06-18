package com.sitionix.forgeai.domain.model.ticket.agentticket;

import java.util.List;
import lombok.Builder;

@Builder
public record ApiLaneEvidencePayload(
        String prUrl,
        String repo,
        List<ApiLaneEvidenceDependency> dependencies
) {
}
