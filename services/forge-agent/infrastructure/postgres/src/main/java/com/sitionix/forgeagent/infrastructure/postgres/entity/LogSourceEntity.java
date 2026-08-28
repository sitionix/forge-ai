package com.sitionix.forgeagent.infrastructure.postgres.entity;

import com.sitionix.forgeagent.domain.model.LogConnectionType;
import com.sitionix.forgeagent.domain.model.LogProviderType;
import com.sitionix.forgeagent.domain.model.LogSourceOwnerType;
import com.sitionix.forgeagent.domain.model.SystemdTargetMode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name="log_sources") @Getter @Setter
public class LogSourceEntity {
    @Id private UUID id;
    @Column(name="project_id", nullable=false) private UUID projectId;
    @Column(nullable=false, length=120) private String name;
    @Enumerated(EnumType.STRING) @Column(name="owner_type", nullable=false, length=24) private LogSourceOwnerType ownerType = LogSourceOwnerType.CUSTOM;
    @Column(name="service_id") private UUID serviceId;
    @Column(name="asset_id") private UUID assetId;
    @Enumerated(EnumType.STRING) @Column(name="connection_type", nullable=false) private LogConnectionType connectionType;
    @Column(name="ssh_connection_id") private UUID sshConnectionId;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private LogProviderType provider;
    @Column(name="docker_container") private String dockerContainer;
    @Column(name="compose_service") private String composeService;
    @Column(name="compose_file", length=1000) private String composeFile;
    @Column(name="systemd_unit") private String systemdUnit;
    @Enumerated(EnumType.STRING) @Column(name="systemd_mode") private SystemdTargetMode systemdMode;
    @Column(name="file_path", length=2000) private String filePath;
    @Column(nullable=false) private boolean enabled;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
}
