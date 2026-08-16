package com.sitionix.forgeagent.api.dto;

import java.util.List;
import java.util.UUID;

public record WorkflowRunGraphResponse(
        UUID taskInputPortId,
        List<RunNodeResponse> nodes,
        List<RunPortResponse> ports,
        List<RunConnectionResponse> connections
) {
}
