package com.sitionix.forgeai.domain.model.codex;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DeployUnitContext {

    private String name;
    private String workflowName;
    private String workflowEvent;
}
