package com.sitionix.forgeagent.infrastructure.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class AgentDependencyId implements Serializable {

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "depends_on_agent_id", nullable = false)
    private UUID dependsOnAgentId;
}
