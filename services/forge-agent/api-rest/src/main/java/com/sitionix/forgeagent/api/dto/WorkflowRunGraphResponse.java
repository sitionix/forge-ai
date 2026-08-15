package com.sitionix.forgeagent.api.dto;

import java.util.List;

public record WorkflowRunGraphResponse(
        List<RunNodeResponse> nodes,
        List<RunPortResponse> ports,
        List<RunConnectionResponse> connections
) {
}
