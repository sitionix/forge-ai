package com.sitionix.forgeagent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record SaveWorkflowRequest(
        @NotBlank @Size(max = 120) String name,
        List<NodeRequest> nodes,
        List<WorkflowConnectionRequest> connections,
        UUID taskInputPortId
) {
}
