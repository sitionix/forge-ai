package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.SshConnection;
import com.sitionix.forgeagent.domain.port.SshConnectionRepository;
import com.sitionix.forgeagent.infrastructure.postgres.entity.SshConnectionEntity;
import com.sitionix.forgeagent.infrastructure.postgres.repository.SpringDataSshConnectionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresSshConnectionRepository implements SshConnectionRepository {

    private final SpringDataSshConnectionRepository repository;

    public List<SshConnection> findByProjectId(final UUID projectId) {
        return this.repository.findAllByProjectIdOrderByNameAscIdAsc(projectId).stream()
                .map(this::domain)
                .toList();
    }

    public Optional<SshConnection> findById(final UUID id) {
        return this.repository.findById(id).map(this::domain);
    }

    public SshConnection save(final SshConnection connection) {
        return this.domain(this.repository.save(this.entity(connection)));
    }

    public void delete(final SshConnection connection) {
        this.repository.deleteById(connection.id());
    }

    private SshConnection domain(final SshConnectionEntity entity) {
        return new SshConnection(
                entity.getId(),
                entity.getProjectId(),
                entity.getName(),
                entity.getHost(),
                entity.getPort(),
                entity.getUsername(),
                entity.getPrivateKeyPath(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private SshConnectionEntity entity(final SshConnection connection) {
        final var entity = new SshConnectionEntity();
        entity.setId(connection.id());
        entity.setProjectId(connection.projectId());
        entity.setName(connection.name());
        entity.setHost(connection.host());
        entity.setPort(connection.port());
        entity.setUsername(connection.username());
        entity.setPrivateKeyPath(connection.privateKeyPath());
        entity.setCreatedAt(connection.createdAt());
        entity.setUpdatedAt(connection.updatedAt());
        return entity;
    }
}
