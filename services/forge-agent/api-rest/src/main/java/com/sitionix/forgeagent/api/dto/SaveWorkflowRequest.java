package com.sitionix.forgeagent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record SaveWorkflowRequest(
        @NotBlank @Size(max = 120) String name,
        List<NodeRequest> nodes,
        List<WorkflowConnectionRequest> connections,
        UUID taskInputPortId,
        UUID taskOutputPortId
) {
    public SaveWorkflowRequest(final String name,
                               final List<NodeRequest> nodes,
                               final List<WorkflowConnectionRequest> connections,
                               final UUID taskInputPortId) {
        this(name, nodes, connections, taskInputPortId, null);
    }
}
