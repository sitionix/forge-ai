package com.sitionix.forgeagent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SaveWorkflowRequest(
        @NotBlank @Size(max = 120) String name,
        List<NodeRequest> nodes
) {
}
