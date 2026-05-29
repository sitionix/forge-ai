package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.ticket.agentticket.ApiLaneEvidencePayload;
import java.util.Set;
import java.util.UUID;

public interface ValidateApiLaneEvidence {

    void validate(UUID laneId, Set<String> callbackContractScopes, ApiLaneEvidencePayload evidencePayload);
}
