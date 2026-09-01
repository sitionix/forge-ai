package com.sitionix.forgeagent.infrastructure.postgres.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "project_assets")
@Getter @Setter
public class ProjectAssetEntity {
  @Id private UUID id;
  @Column(name="project_id", nullable=false) private UUID projectId;
  @Column(nullable=false, length=120) private String name;
  @Column(name="ssh_connection_id", nullable=false) private UUID sshConnectionId;
  @Column(name="created_at", nullable=false) private Instant createdAt;
  @Column(name="updated_at", nullable=false) private Instant updatedAt;
}
