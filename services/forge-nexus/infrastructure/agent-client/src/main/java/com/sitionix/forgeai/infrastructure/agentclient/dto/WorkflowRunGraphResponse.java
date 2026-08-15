package com.sitionix.forgeai.infrastructure.agentclient.dto;

import java.util.List;

public record WorkflowRunGraphResponse(
        List<RunNodeResponse> nodes,
        List<RunPortResponse> ports,
        List<RunConnectionResponse> connections
) {
}
