package com.sitionix.forgeagent.infrastructure.postgres.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "agent_dependencies")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AgentDependencyEntity {

    @EmbeddedId
    private AgentDependencyId id;
}
