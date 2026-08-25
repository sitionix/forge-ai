package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.LogSourceRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.LogSourceEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataLogSourceRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository @RequiredArgsConstructor
public class PostgresLogSourceRepository implements LogSourceRepository {
    private final SpringDataLogSourceRepository repository;
    public List<LogSource> findByProjectId(UUID id) { return repository.findAllByProjectIdOrderByNameAscIdAsc(id).stream().map(this::domain).toList(); }
    public Optional<LogSource> findById(UUID id) { return repository.findById(id).map(this::domain); }
    public LogSource save(LogSource source) { return domain(repository.save(entity(source))); }
    public void delete(LogSource source) { repository.deleteById(source.id()); }
    private LogSource domain(LogSourceEntity e) {
        LogProviderConfiguration c = switch (e.getProvider()) {
            case DOCKER -> new DockerLogConfiguration(e.getDockerContainer(), e.getComposeService(), e.getComposeFile());
            case SYSTEMD -> new SystemdLogConfiguration(e.getSystemdUnit());
            case FILE -> new FileLogConfiguration(e.getFilePath());
        };
        return new LogSource(e.getId(),e.getProjectId(),e.getName(),e.getServiceId(),e.getConnectionType(),e.getSshConnectionId(),e.getProvider(),c,e.isEnabled(),e.getCreatedAt(),e.getUpdatedAt());
    }
    private LogSourceEntity entity(LogSource s) {
        var e=new LogSourceEntity(); e.setId(s.id());e.setProjectId(s.projectId());e.setName(s.name());e.setServiceId(s.serviceId());e.setConnectionType(s.connectionType());e.setSshConnectionId(s.sshConnectionId());e.setProvider(s.provider());e.setEnabled(s.enabled());e.setCreatedAt(s.createdAt());e.setUpdatedAt(s.updatedAt());
        switch(s.configuration()) {
            case DockerLogConfiguration c -> { e.setDockerContainer(c.container());e.setComposeService(c.composeService());e.setComposeFile(c.composeFile()); }
            case SystemdLogConfiguration c -> e.setSystemdUnit(c.unit());
            case FileLogConfiguration c -> e.setFilePath(c.path());
        }
        return e;
    }
}
