package com.sitionix.forgeagent.infrastructure.postgres.entity;
import com.sitionix.forgeagent.domain.model.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter; import lombok.Setter;
@Entity @Table(name="project_services") @Getter @Setter
public class ProjectServiceEntity {
 @Id private UUID id;
 @Column(name="project_id",nullable=false) private UUID projectId;
 @Column(nullable=false,length=120) private String name;
 @Column(name="repository_id") private UUID repositoryId;
 @Enumerated(EnumType.STRING) @Column(name="connection_type",nullable=false) private ServiceConnectionType connectionType;
 @Column(name="ssh_connection_id") private UUID sshConnectionId;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private ServiceRuntimeProvider provider;
 @Column(name="docker_container") private String dockerContainer;
 @Column(name="systemd_unit") private String systemdUnit;
 @Column(name="created_at",nullable=false) private Instant createdAt;
 @Column(name="updated_at",nullable=false) private Instant updatedAt;
}
