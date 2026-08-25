package com.sitionix.forgeagent.infrastructure.postgres.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "ssh_connections") @Getter @Setter
public class SshConnectionEntity {
    @Id private UUID id;
    @Column(name="project_id", nullable=false) private UUID projectId;
    @Column(nullable=false, length=120) private String name;
    @Column(nullable=false) private String host;
    @Column(nullable=false) private int port;
    @Column(nullable=false, length=120) private String username;
    @Column(name="private_key_path", nullable=false, length=1000) private String privateKeyPath;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
}
