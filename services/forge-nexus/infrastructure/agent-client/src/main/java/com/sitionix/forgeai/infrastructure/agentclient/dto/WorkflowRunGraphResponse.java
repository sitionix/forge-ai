package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.List;
import java.util.UUID;

public record WorkflowRunGraphResponse(
        UUID taskInputPortId,
        List<RunNodeResponse> nodes,
        List<RunPortResponse> ports,
        List<RunConnectionResponse> connections
) {
}
