package com.sitionix.forgeagent.infrastructure.postgres.entity;

import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class WorkflowNodeEntityId implements Serializable {
    private UUID workflowId;
    private UUID id;
}
