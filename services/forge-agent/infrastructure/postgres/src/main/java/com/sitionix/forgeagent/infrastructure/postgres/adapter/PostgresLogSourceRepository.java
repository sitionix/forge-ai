package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.DockerLogConfiguration;
import com.sitionix.forgeagent.domain.model.FileLogConfiguration;
import com.sitionix.forgeagent.domain.model.LogProviderConfiguration;
import com.sitionix.forgeagent.domain.model.LogSource;
import com.sitionix.forgeagent.domain.model.SystemdLogConfiguration;
import com.sitionix.forgeagent.domain.port.LogSourceRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.LogSourceEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataLogSourceRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresLogSourceRepository implements LogSourceRepository {

    private final SpringDataLogSourceRepository repository;

    public List<LogSource> findByProjectId(final UUID projectId) {
        return this.repository.findAllByProjectIdOrderByNameAscIdAsc(projectId).stream()
                .map(this::domain)
                .toList();
    }

    @Override
    public List<LogSource> findByProjectIdAndServiceId(final UUID projectId, final UUID serviceId) {
        return repository.findAllByProjectIdAndServiceIdOrderByNameAscIdAsc(projectId, serviceId).stream().map(this::domain).toList();
    }

    public Optional<LogSource> findById(final UUID id) {
        return this.repository.findById(id).map(this::domain);
    }

    public LogSource save(final LogSource source) {
        return this.domain(this.repository.save(this.entity(source)));
    }

    public void delete(final LogSource source) {
        this.repository.deleteById(source.id());
    }

    private LogSource domain(final LogSourceEntity entity) {
        final LogProviderConfiguration configuration = switch (entity.getProvider()) {
            case DOCKER -> new DockerLogConfiguration(
                    entity.getDockerContainer(), entity.getComposeService(), entity.getComposeFile());
            case SYSTEMD -> new SystemdLogConfiguration(entity.getSystemdMode(), entity.getSystemdUnit());
            case FILE -> new FileLogConfiguration(entity.getFilePath());
        };
        return new LogSource(
                entity.getId(),
                entity.getProjectId(),
                entity.getName(),
                entity.getServiceId(),
                entity.getConnectionType(),
                entity.getSshConnectionId(),
                entity.getProvider(),
                configuration,
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private LogSourceEntity entity(final LogSource source) {
        final var entity = new LogSourceEntity();
        entity.setId(source.id());
        entity.setProjectId(source.projectId());
        entity.setName(source.name());
        entity.setServiceId(source.serviceId());
        entity.setConnectionType(source.connectionType());
        entity.setSshConnectionId(source.sshConnectionId());
        entity.setProvider(source.provider());
        entity.setEnabled(source.enabled());
        entity.setCreatedAt(source.createdAt());
        entity.setUpdatedAt(source.updatedAt());
        switch (source.configuration()) {
            case DockerLogConfiguration docker -> {
                entity.setDockerContainer(docker.container());
                entity.setComposeService(docker.composeService());
                entity.setComposeFile(docker.composeFile());
            }
            case SystemdLogConfiguration systemd -> {
                entity.setSystemdMode(systemd.mode());
                entity.setSystemdUnit(systemd.unit());
            }
            case FileLogConfiguration file -> entity.setFilePath(file.path());
        }
        return entity;
    }
}
